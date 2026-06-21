"""Fashion-Radar AI 분석 워커 (설계서 2.6).

Spring 서버가 Redis 큐(queue:scan)에 적재한 작업을 소비한다:
  1. S3에서 원본 이미지 다운로드
  2. 행인(Bystander) 얼굴 검출 → 본인 추정 얼굴(최대 크기) 제외하고 블러 → 재업로드
  3. 동일 이미지 해시 캐시 조회 (같은 사진 재분석 방지 — 비용 통제)
  4. Gemini Vision 태깅 (API 키 없으면 스텁 태깅으로 폴백)
  5. clothes_item 업데이트(DONE/FAILED) 후 완료 알림 발행

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
    """
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
ANALYSIS_PROMPT = """이 사진 속 착장을 분석해 JSON으로만 답하세요:
{"category": "상의/하의/아우터/신발/액세서리 중 하나",
 "brand_guess": "추정 브랜드 또는 null",
 "color": "주요 색상",
 "style_tags": ["스트릿", "미니멀" 등 태그 배열]}"""


def analyze_with_gemini(image_bytes: bytes) -> dict:
    if not settings.GEMINI_API_KEY:
        log.info("GEMINI_API_KEY 없음 — 스텁 태깅 사용")
        return {"category": "상의", "brand_guess": None,
                "color": "unknown", "style_tags": ["stub"]}

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

    # 동일 이미지 해시 캐싱 — 같은 사진 재분석 방지 (설계서 2.6)
    digest = hashlib.sha256(original).hexdigest()
    cache_key = f"imghash:{digest}"
    cached = r.get(cache_key)
    if cached:
        result = json.loads(cached)
        log.info("해시 캐시 적중 item=%s — Gemini 호출 생략", item_id)
    else:
        result = analyze_with_gemini(blurred)
        r.set(cache_key, json.dumps(result, ensure_ascii=False), ex=60 * 60 * 24 * 30)

    meta = {k: v for k, v in result.items() if k not in ("category", "brand_guess")}
    with psycopg.connect(settings.DATABASE_URL) as conn:
        conn.execute(
            """UPDATE clothes_item
               SET category = %s, brand_info = %s, meta_data = %s::jsonb, scan_status = 'DONE'
               WHERE id = %s""",
            (result.get("category"), result.get("brand_guess"),
             json.dumps(meta, ensure_ascii=False), item_id),
        )

    # 완료 알림 — Spring이 이 채널을 STOMP(/topic/scan/{user_id})로 릴레이
    r.publish(f"{settings.RESULT_CHANNEL_PREFIX}{user_id}", json.dumps({
        "type": "SCAN_DONE", "item_id": item_id, "scan_status": "DONE",
    }))
    log.info("작업 완료 item=%s", item_id)


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


def main() -> None:
    log.info("스캔 워커 시작 — 큐: %s", settings.SCAN_QUEUE)
    while True:
        popped = r.brpop(settings.SCAN_QUEUE, timeout=5)
        if popped is None:
            continue
        job = None
        try:
            job = json.loads(popped[1])
            process(job)
        except Exception:
            log.exception("작업 처리 실패: %s", popped[1])
            if job:
                mark_failed(job)
            time.sleep(1)  # 연쇄 실패 시 폭주 방지


if __name__ == "__main__":
    main()
