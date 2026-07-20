"""Fashion-Radar AI 분석 워커 (설계서 2.6).

Spring 서버가 Redis 큐(queue:scan)에 적재한 작업을 소비한다:
  1. S3에서 원본 이미지 다운로드
  2. 행인(Bystander) 얼굴 검출 → 본인 추정 얼굴(최대 크기) 제외하고 블러 → 재업로드
  3. 동일 이미지 해시 캐시 조회 (같은 사진 재분석 방지 — 비용 통제)
  4. Gemini Vision 태깅 (API 키 없으면 스텁 태깅으로 폴백)
  5. clothes_item 업데이트(DONE/FAILED) 후 완료 알림 발행
  6. 옷장 최신 착장으로 3D 아바타 GLB 재생성 → S3 업로드 (설계서 4.2)

실행: python worker.py
"""
import hashlib
import io
import json
import logging
import time

import boto3
import psycopg
import redis

import avatar_builder
import product_info
import settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("scan-worker")

s3 = boto3.client(
    "s3",
    endpoint_url=settings.S3_ENDPOINT,
    aws_access_key_id=settings.S3_ACCESS_KEY,
    aws_secret_access_key=settings.S3_SECRET_KEY,
)
r = redis.Redis.from_url(settings.REDIS_URL, decode_responses=True)


# ---------------------------------------------------------------- 얼굴 블러
def blur_bystander_faces(image_bytes: bytes) -> bytes:
    """거울 셀카 속 타인 얼굴 자동 블러 (설계서 1.2 행인 보호).

    MVP 휴리스틱: 검출된 얼굴 중 가장 큰 얼굴을 촬영자 본인으로 간주하고
    나머지를 블러 처리한다. Vision API 호출 전 로컬 선처리라 추가 비용이 없다.
    선처리는 best-effort — 환경 문제(opencv 미설치/버전 비호환)로 스캔 전체를
    실패시키지 않고 원본으로 진행한다.
    """
    try:
        return _blur_bystander_faces(image_bytes)
    except Exception:
        log.exception("얼굴 블러 실패 — 원본으로 진행")
        return image_bytes


def _blur_bystander_faces(image_bytes: bytes) -> bytes:
    try:
        import cv2
        import numpy as np
    except ImportError:
        log.warning("opencv 미설치 — 얼굴 블러 건너뜀")
        return image_bytes

    arr = np.frombuffer(image_bytes, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        return image_bytes

    cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    faces = cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(40, 40))
    if len(faces) <= 1:
        return image_bytes  # 본인 혼자거나 얼굴 미검출

    owner = max(faces, key=lambda f: f[2] * f[3])
    for (x, y, w, h) in faces:
        if (x, y, w, h) == tuple(owner):
            continue
        roi = img[y:y + h, x:x + w]
        img[y:y + h, x:x + w] = cv2.GaussianBlur(roi, (51, 51), 30)

    ok, encoded = cv2.imencode(".jpg", img)
    return encoded.tobytes() if ok else image_bytes


# ---------------------------------------------------------------- Gemini 태깅
ANALYSIS_PROMPT = """이 사진에 보이는 모든 의류/패션 아이템을 분석해 JSON으로만 답하세요:
{"items": [
  {"category": "상의/하의/아우터/신발/액세서리 중 하나",
   "brand_guess": "추정 브랜드 또는 null",
   "color": "주요 색상",
   "style_tags": ["스트릿", "미니멀" 등 태그 배열]}
 ],
 "hair": {"style": "short/long/bald 중 하나", "color": "black/brown/blonde/red/blue/pink 중 가장 가까운 것"}}
착용 중인 아이템을 각각 하나의 원소로 넣으세요 (전신 사진이면 보통 2~4개, 단일 상품 사진이면 1개).
가장 대표적인 아이템을 배열의 첫 번째로 하세요. 사람 머리가 보이지 않으면 "hair"는 null."""


def analyze_with_gemini(image_bytes: bytes) -> dict:
    if not settings.GEMINI_API_KEY:
        log.info("GEMINI_API_KEY 없음 — 스텁 태깅 사용")
        return {"items": [{"category": "상의", "brand_guess": None,
                           "color": "unknown", "style_tags": ["stub"]}],
                "hair": None}

    from google import genai
    from google.genai import types

    client = genai.Client(api_key=settings.GEMINI_API_KEY)
    response = client.models.generate_content(
        model=settings.GEMINI_MODEL,
        contents=[
            types.Part.from_bytes(data=image_bytes, mime_type="image/jpeg"),
            ANALYSIS_PROMPT,
        ],
    )
    text = response.text.strip()
    if text.startswith("```"):
        text = text.strip("`").removeprefix("json").strip()
    return json.loads(text)


# ---------------------------------------------------------------- 작업 처리
def process(job: dict) -> None:
    item_id, user_id, image_key = job["item_id"], job["user_id"], job["image_key"]
    log.info("작업 시작 item=%s key=%s", item_id, image_key)

    obj = s3.get_object(Bucket=settings.S3_BUCKET, Key=image_key)
    original = obj["Body"].read()

    blurred = blur_bystander_faces(original)
    if blurred is not original:
        s3.put_object(Bucket=settings.S3_BUCKET, Key=image_key,
                      Body=blurred, ContentType="image/jpeg")
        log.info("행인 얼굴 블러 적용 후 재업로드 item=%s", item_id)

    # 상품 URL 메타데이터 강화 — 실패해도 촬영본 분석으로 폴백 (SSRF 방어는 product_info)
    product = {}
    if job.get("product_url"):
        try:
            product = product_info.fetch_og(job["product_url"])
        except Exception:
            log.warning("상품 페이지 조회 실패 item=%s url=%s", item_id, job["product_url"])

    # 상품컷이 있으면 그걸로 분석 — 셀카보다 정면·무배경이라 태깅 정확도가 높다
    analysis_image = blurred
    if product.get("image"):
        try:
            analysis_image = product_info.download_image(product["image"])
            log.info("상품컷 기준 분석 item=%s", item_id)
        except Exception:
            log.warning("상품컷 다운로드 실패 item=%s — 촬영본으로 분석", item_id)

    # 동일 이미지 해시 캐싱 — 같은 사진 재분석 방지 (설계서 2.6). 분석에 쓴 이미지 기준.
    # 키에 결과 형식 버전 포함 — 프롬프트/스키마가 바뀌면 옛 캐시를 자연 무효화.
    digest = hashlib.sha256(analysis_image).hexdigest()
    cache_key = f"imghash:{digest}:v2"
    cached = r.get(cache_key)
    if cached:
        result = json.loads(cached)
        log.info("해시 캐시 적중 item=%s — Gemini 호출 생략", item_id)
    else:
        result = analyze_with_gemini(analysis_image)
        r.set(cache_key, json.dumps(result, ensure_ascii=False), ex=60 * 60 * 24 * 30)

    # 전신 사진 = 여러 아이템: 첫 번째(대표)는 기존 행 갱신, 나머지는 새 행으로 등록
    detected_items = result.get("items") or [result]  # 구형 단일 응답 방어
    with psycopg.connect(settings.DATABASE_URL) as conn:
        for index, detected in enumerate(detected_items):
            meta = {k: v for k, v in detected.items()
                    if k not in ("category", "brand_guess")}
            brand = detected.get("brand_guess")
            if index == 0:
                if product.get("title"):
                    meta["product_name"] = product["title"]
                # 브랜드: Vision 추정이 없으면 쇼핑몰 사이트명으로 보강
                brand = brand or product.get("site_name")
                conn.execute(
                    """UPDATE clothes_item
                       SET category = %s, brand_info = %s, meta_data = %s::jsonb,
                           scan_status = 'DONE'
                       WHERE id = %s""",
                    (detected.get("category"), brand,
                     json.dumps(meta, ensure_ascii=False), item_id),
                )
            else:
                conn.execute(
                    """INSERT INTO clothes_item
                       (user_id, category, brand_info, meta_data, image_key, scan_status)
                       VALUES (%s, %s, %s, %s::jsonb, %s, 'DONE')""",
                    (user_id, detected.get("category"), brand,
                     json.dumps(meta, ensure_ascii=False), image_key),
                )
        if len(detected_items) > 1:
            log.info("전신 사진에서 %d개 아이템 추출 item=%s", len(detected_items), item_id)

        # 헤어 자동 반영 — 헤어도 패션의 일부. 단, 본인 촬영본을 분석했을 때만.
        # (상품컷으로 분석한 경우 사진 속 모델의 머리를 따라가면 안 된다)
        hair = result.get("hair") if analysis_image is blurred else None
        if isinstance(hair, dict):
            detected = {key: value for key, value in (
                ("detected_hair_style", hair.get("style")),
                ("detected_hair_color", hair.get("color"))) if value}
            if detected:
                conn.execute(
                    """UPDATE users
                       SET avatar_config = coalesce(avatar_config, '{}'::jsonb) || %s::jsonb
                       WHERE id = %s""",
                    (json.dumps(detected), user_id),
                )

    # 아바타 재생성은 부가 작업 — 실패해도 스캔 자체(DONE)는 유지한다
    try:
        rebuild_avatar(user_id)
    except Exception:
        log.exception("아바타 재생성 실패 user=%s (스캔 결과는 유지)", user_id)

    # 완료 알림 — Spring이 이 채널을 STOMP(/topic/scan/{user_id})로 릴레이
    r.publish(f"{settings.RESULT_CHANNEL_PREFIX}{user_id}", json.dumps({
        "type": "SCAN_DONE", "item_id": item_id, "scan_status": "DONE",
    }))
    log.info("작업 완료 item=%s", item_id)


# ---------------------------------------------------------------- 3D 아바타
def rebuild_avatar(user_id: int) -> None:
    """옷장 태그로 블록 아바타 GLB를 만들어 S3에 올린다.

    착장 기준: 유저가 고른 '오늘의 코디'(daily_codi)가 있으면 그 조합,
    없으면 카테고리별 최신 DONE 아이템 폴백. 사진 재분석 없이 DB 태그만 쓴다.
    실사 3D 스캔이 아닌 태그 기반 절차 생성이라 얼굴/신체 데이터가 남지 않는다
    (설계서 1.2 프라이버시 원칙). 항상 같은 키를 덮어써서 CDN 키가 안정적이다.
    """
    with psycopg.connect(settings.DATABASE_URL) as conn:
        rows = conn.execute(
            """SELECT DISTINCT ON (ci.category) ci.category, ci.meta_data
               FROM daily_codi dc
               JOIN daily_codi_item dci ON dci.codi_id = dc.id
               JOIN clothes_item ci ON ci.id = dci.item_id
               WHERE dc.user_id = %s
                 AND ci.scan_status = 'DONE' AND ci.category IS NOT NULL
               ORDER BY ci.category, ci.created_at DESC""",
            (user_id,),
        ).fetchall()
        if not rows:  # 코디 미설정 → 최신 아이템 조합 폴백
            rows = conn.execute(
                """SELECT DISTINCT ON (category) category, meta_data
                   FROM clothes_item
                   WHERE user_id = %s AND scan_status = 'DONE' AND category IS NOT NULL
                   ORDER BY category, created_at DESC""",
                (user_id,),
            ).fetchall()
        outfit = avatar_builder.outfit_from_items(
            [{"category": c, "meta_data": m} for c, m in rows])

        # 베이스 파라미터(피부/헤어) — 가입 시 1회 생성, 마이페이지에서 변경 (avatar_config)
        config_row = conn.execute(
            "SELECT avatar_config FROM users WHERE id = %s", (user_id,)).fetchone()
        config = config_row[0] if config_row and config_row[0] else {}

        glb = avatar_builder.build_avatar_glb(outfit, config)
        key = f"avatars/u{user_id}.glb"
        s3.put_object(Bucket=settings.S3_BUCKET, Key=key,
                      Body=glb, ContentType="model/gltf-binary")

        conn.execute("UPDATE users SET avatar_bundle_key = %s WHERE id = %s",
                     (key, user_id))
    log.info("아바타 재생성 완료 user=%s slots=%s", user_id, sorted(outfit))


def mark_failed(job: dict) -> None:
    try:
        with psycopg.connect(settings.DATABASE_URL) as conn:
            conn.execute("UPDATE clothes_item SET scan_status = 'FAILED' WHERE id = %s",
                         (job["item_id"],))
        r.publish(f"{settings.RESULT_CHANNEL_PREFIX}{job['user_id']}", json.dumps({
            "type": "SCAN_FAILED", "item_id": job["item_id"], "scan_status": "FAILED",
        }))
    except Exception:
        log.exception("실패 상태 기록 중 오류")


def handle(job: dict) -> None:
    """잡 라우팅 — AVATAR_ONLY(코디 변경)는 스캔 없이 아바타만 재생성한다."""
    if job.get("type") == "AVATAR_ONLY":
        user_id = job["user_id"]
        rebuild_avatar(user_id)
        r.publish(f"{settings.RESULT_CHANNEL_PREFIX}{user_id}", json.dumps({
            "type": "AVATAR_UPDATED",
        }))
        return
    process(job)


def main() -> None:
    log.info("스캔 워커 시작 — 큐: %s", settings.SCAN_QUEUE)
    while True:
        popped = r.brpop(settings.SCAN_QUEUE, timeout=5)
        if popped is None:
            continue
        job = None
        try:
            job = json.loads(popped[1])
            handle(job)
        except Exception:
            log.exception("작업 처리 실패: %s", popped[1])
            if job and "item_id" in job:  # 스캔 잡만 FAILED 마킹 (아바타 잡은 해당 없음)
                mark_failed(job)
            time.sleep(1)  # 연쇄 실패 시 폭주 방지


if __name__ == "__main__":
    main()
