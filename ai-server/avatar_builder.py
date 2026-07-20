"""절차적 3D 아바타 생성 v2 — 둥근 카툰 스타일 (설계서 4.2 avatar_bundle_url).

Vision 태깅 결과(카테고리/색상)만으로 캐릭터 GLB를 만든다.
실사 신체를 3D화하지 않으므로 행인 보호·익명성 원칙(설계서 1.2/2.2)과 충돌이 없고,
생성 비용이 0이라 스캔 때마다 즉시 재생성할 수 있다.
앱은 model-viewer 계열 뷰어로 GLB를 로드해 360° 회전(앞뒤 양옆)으로 보여준다.

v2 변경점: 박스 → 라운드 지오메트리, 2.5등신 카툰 비율, 눈 있는 얼굴,
카테고리별 옷 형태 분기(스커트/원피스/반바지/후드), 아우터 착용 시 소매 색 반영.
"""
import numpy as np
import trimesh

# 아바타 베이스 파라미터 (users.avatar_config) — 가입 시 1회 선택, 이후 변경 가능.
# 값이 없거나 모르는 값이면 첫 번째 항목으로 폴백해 절대 실패하지 않는다.
SKIN_TONES = {
    "light": (0.96, 0.80, 0.69),
    "tan": (0.80, 0.60, 0.45),
    "deep": (0.45, 0.30, 0.22),
}
HAIR_COLORS = {
    "black": (0.13, 0.12, 0.13),
    "brown": (0.35, 0.22, 0.12),
    "blonde": (0.85, 0.72, 0.45),
    "red": (0.55, 0.20, 0.12),
    "blue": (0.25, 0.35, 0.75),
    "pink": (0.90, 0.55, 0.65),
}
HAIR_STYLES = ("short", "long", "bald")

_EYE = (0.12, 0.10, 0.10)
_DEFAULT_CLOTH = (0.62, 0.62, 0.68)

# Gemini가 뱉는 색상 문자열(한/영 혼용) → RGB. 부분 문자열 매칭.
_COLOR_MAP = [
    (("검정", "블랙", "black"), (0.15, 0.15, 0.17)),
    (("흰", "화이트", "아이보리", "white", "ivory"), (0.94, 0.93, 0.91)),
    (("회색", "그레이", "gray", "grey"), (0.58, 0.58, 0.60)),
    (("네이비", "navy"), (0.13, 0.18, 0.35)),
    (("청", "데님", "denim", "파랑", "파란", "블루", "blue"), (0.28, 0.42, 0.72)),
    (("빨강", "빨간", "레드", "red"), (0.78, 0.24, 0.22)),
    (("카키", "올리브", "khaki", "olive"), (0.45, 0.45, 0.30)),
    (("초록", "그린", "green"), (0.25, 0.55, 0.35)),
    (("베이지", "크림", "beige", "cream", "tan"), (0.86, 0.79, 0.65)),
    (("갈색", "브라운", "brown"), (0.46, 0.32, 0.22)),
    (("노랑", "노란", "옐로", "yellow"), (0.92, 0.80, 0.30)),
    (("핑크", "분홍", "pink"), (0.93, 0.65, 0.73)),
    (("보라", "퍼플", "purple"), (0.55, 0.38, 0.65)),
    (("주황", "오렌지", "orange"), (0.90, 0.55, 0.25)),
]

# 자유 텍스트 카테고리 → 표준 슬롯. Gemini 프롬프트는 5종 고정이지만 방어적으로 매칭.
_CATEGORY_KEYWORDS = [
    ("outer", ("아우터", "자켓", "재킷", "코트", "점퍼", "패딩", "outer", "jacket", "coat")),
    ("top", ("상의", "셔츠", "티", "니트", "후드", "원피스", "드레스", "top", "shirt", "tee", "hoodie", "dress")),
    ("bottom", ("하의", "바지", "팬츠", "스커트", "치마", "진", "레깅스", "bottom", "pants", "skirt", "jeans")),
    ("shoes", ("신발", "슈즈", "스니커", "부츠", "샌들", "shoes", "sneaker", "boots", "sandal")),
    ("accessory", ("액세서리", "모자", "캡", "acc", "hat", "cap")),
]


def color_of(text: str | None) -> tuple:
    if text:
        lowered = text.lower()
        for keywords, rgb in _COLOR_MAP:
            if any(k in lowered for k in keywords):
                return rgb
    return _DEFAULT_CLOTH


def slot_of(category: str | None) -> str | None:
    if not category:
        return None
    lowered = category.lower()
    for slot, keywords in _CATEGORY_KEYWORDS:
        if any(k in lowered for k in keywords):
            return slot
    return None


def resolve_config(config: dict | None) -> tuple:
    """avatar_config → (피부 RGB, 헤어 RGB, 헤어스타일).

    헤어 우선순위: 수동 선택(팔레트 값) > 사진 감지(detected_* — 워커가 기록) > 기본값.
    "auto"·미지정·오타는 전부 감지값 폴백 경로를 타므로 워커가 절대 실패하지 않는다.
    """
    config = config or {}
    skin = SKIN_TONES.get(config.get("skin"), SKIN_TONES["light"])

    style = config.get("hair_style")
    if style not in HAIR_STYLES:  # "auto" 포함 — 사진 감지값으로
        detected = config.get("detected_hair_style")
        style = detected if detected in HAIR_STYLES else "short"

    color_key = config.get("hair_color")
    if color_key not in HAIR_COLORS:  # "auto" 포함 — 사진 감지값으로
        detected = config.get("detected_hair_color")
        color_key = detected if detected in HAIR_COLORS else "black"

    return skin, HAIR_COLORS[color_key], style


# ---------------------------------------------------------------- 지오메트리
def _ellipsoid(name: str, scene: trimesh.Scene, radii, center, rgb,
               texture=None) -> None:
    """라운드 파트의 기본 단위 — 축별 반지름을 가진 타원체.

    texture(PIL.Image)가 있으면 구면 UV로 래핑한다 — 사진 크롭의 무늬·주름 음영이
    옷 파트에 그대로 실린다. 정면(+z)이 텍스처 중앙, 이음새는 등 뒤(-z).
    """
    mesh = trimesh.creation.icosphere(subdivisions=2)
    if texture is not None:
        # 단위 구 정점 기준 구면 UV (스케일 전 계산)
        x, y, z = mesh.vertices.T
        u = 0.5 + np.arctan2(x, z) / (2.0 * np.pi)
        v = 0.5 + np.arcsin(np.clip(y, -1.0, 1.0)) / np.pi
        material = trimesh.visual.material.PBRMaterial(
            name=f"{name}-mat", baseColorTexture=texture,
            metallicFactor=0.0, roughnessFactor=0.9)
        mesh.visual = trimesh.visual.TextureVisuals(
            uv=np.column_stack([u, v]), material=material)
    else:
        mesh.visual = trimesh.visual.TextureVisuals(
            material=trimesh.visual.material.PBRMaterial(
                name=f"{name}-mat",
                baseColorFactor=[*rgb, 1.0],
                metallicFactor=0.0,
                roughnessFactor=0.85))
    mesh.apply_transform(np.diag([radii[0], radii[1], radii[2], 1.0]))
    mesh.apply_translation(center)
    scene.add_geometry(mesh, node_name=name, geom_name=name)


def _norm_outfit(outfit: dict | None) -> dict:
    """v2 형식 {slot: {color, category, texture?}} — 구형 {slot: 색상문자열}도 허용."""
    normalized = {}
    for slot, value in (outfit or {}).items():
        if isinstance(value, dict):
            normalized[slot] = {"color": value.get("color") or "",
                                "category": value.get("category") or "",
                                "texture": value.get("texture")}
        else:
            normalized[slot] = {"color": value or "", "category": "", "texture": None}
    return normalized


def _has_kw(entry: dict | None, *keywords) -> bool:
    return bool(entry) and any(k in entry["category"] for k in keywords)


def build_avatar_glb(outfit: dict, config: dict | None = None) -> bytes:
    """outfit: {"top": {"color": "블랙", "category": "후드"}, ...} / config: 베이스 파라미터.

    Y-up, 바닥 y=0, 약 6등신 패션 일러스트 비율(총 높이 약 1.43, 머리 0.25).
    옷 실루엣이 잘 보이도록 다리를 길게, 팔다리를 슬림하게 잡는다.
    """
    o = _norm_outfit(outfit)
    skin, hair_rgb, hair_style = resolve_config(config)
    top, bottom = o.get("top"), o.get("bottom")
    outer, shoes, acc = o.get("outer"), o.get("shoes"), o.get("accessory")

    top_rgb = color_of(top["color"] if top else None)
    bottom_rgb = color_of(bottom["color"] if bottom else None)
    shoes_rgb = color_of(shoes["color"] if shoes else None)
    # 사진 크롭 텍스처 (없으면 단색) — 큰 파트(몸통/다리/치마/아우터)에만 적용
    top_tex = top.get("texture") if top else None
    bottom_tex = bottom.get("texture") if bottom else None
    outer_tex = outer.get("texture") if outer else None

    # 카테고리 기반 형태 분기
    dress = _has_kw(top, "원피스", "드레스")
    skirt = dress or _has_kw(bottom, "스커트", "치마")
    shorts = not skirt and _has_kw(bottom, "반바지", "쇼츠")
    hood_src = outer if _has_kw(outer, "후드") else (top if _has_kw(top, "후드") else None)

    scene = trimesh.Scene()

    # 다리(0~0.74) — 스커트/원피스면 맨다리(피부색), 반바지면 상단만 옷 색
    for side, x in (("l", -0.075), ("r", 0.075)):
        if skirt:
            _ellipsoid(f"leg-{side}", scene, (0.062, 0.35, 0.062), (x, 0.38, 0.0), skin)
        elif shorts:
            _ellipsoid(f"leg-{side}", scene, (0.075, 0.19, 0.075), (x, 0.55, 0.0),
                       bottom_rgb, texture=bottom_tex)
            _ellipsoid(f"leg-{side}-lower", scene, (0.055, 0.19, 0.055), (x, 0.21, 0.0), skin)
        else:
            _ellipsoid(f"leg-{side}", scene, (0.072, 0.36, 0.072), (x, 0.38, 0.0),
                       bottom_rgb, texture=bottom_tex)
        _ellipsoid(f"foot-{side}", scene, (0.075, 0.05, 0.13), (x, 0.045, 0.04), shoes_rgb)

    # 스커트 — A라인 느낌의 납작 타원체 (원피스면 상의 색)
    if skirt:
        _ellipsoid("skirt", scene, (0.21, 0.16, 0.17), (0.0, 0.66, 0.0),
                   top_rgb if dress else bottom_rgb,
                   texture=top_tex if dress else bottom_tex)

    # 몸통(상의, 0.71~1.17) + 아우터 셸
    _ellipsoid("torso", scene, (0.16, 0.23, 0.115), (0.0, 0.94, 0.0), top_rgb,
               texture=top_tex)
    arm_rgb = top_rgb
    if outer:
        outer_rgb = color_of(outer["color"])
        _ellipsoid("outer", scene, (0.185, 0.245, 0.145), (0.0, 0.945, 0.0), outer_rgb,
                   texture=outer_tex)
        _ellipsoid("collar", scene, (0.105, 0.04, 0.09), (0.0, 1.165, 0.01), outer_rgb)
        arm_rgb = outer_rgb  # 소매는 겉옷 색

    # 팔 — 어깨(1.10)에서 허리 아래까지, 슬림하게
    for side, x in (("l", -0.205), ("r", 0.205)):
        _ellipsoid(f"arm-{side}", scene, (0.048, 0.21, 0.048), (x, 0.90, 0.0), arm_rgb)
        _ellipsoid(f"hand-{side}", scene, (0.042, 0.045, 0.042), (x, 0.665, 0.0), skin)

    # 머리(중심 1.30, 높이 0.25) + 눈
    _ellipsoid("head", scene, (0.115, 0.125, 0.115), (0.0, 1.30, 0.0), skin)
    for side, x in (("l", -0.045), ("r", 0.045)):
        _ellipsoid(f"eye-{side}", scene, (0.016, 0.022, 0.012), (x, 1.305, 0.107), _EYE)

    # 헤어 — 뒤통수 중심의 캡 + 이마 위 앞머리. 얼굴(눈 주변)은 열어둔다.
    if hair_style != "bald":
        _ellipsoid("hair", scene, (0.125, 0.125, 0.108), (0.0, 1.318, -0.033), hair_rgb)
        _ellipsoid("hair-bang", scene, (0.103, 0.042, 0.046), (0.0, 1.398, 0.066), hair_rgb)
        if hair_style == "long":
            _ellipsoid("hair-back", scene, (0.105, 0.21, 0.055), (0.0, 1.13, -0.085), hair_rgb)

    # 후드 — 목 뒤의 볼록한 후드 덩어리
    if hood_src is not None:
        _ellipsoid("hood", scene, (0.085, 0.065, 0.055), (0.0, 1.155, -0.115),
                   color_of(hood_src["color"]))

    # 액세서리(모자) — 챙 있는 캡
    if acc:
        acc_rgb = color_of(acc["color"])
        _ellipsoid("cap", scene, (0.12, 0.05, 0.112), (0.0, 1.428, -0.012), acc_rgb)
        _ellipsoid("cap-brim", scene, (0.088, 0.014, 0.065), (0.0, 1.408, 0.125), acc_rgb)

    return scene.export(file_type="glb")


def outfit_from_items(items: list[dict]) -> dict:
    """clothes_item 행 목록 → 슬롯별 {색상, 카테고리, crop_key}. 같은 슬롯이면 최신 우선.

    crop_key는 워커가 사진에서 잘라 S3에 올려둔 옷 영역 이미지의 키 —
    rebuild_avatar가 다운로드해 texture로 바꿔 넣는다.
    """
    outfit: dict = {}
    for item in items:
        category = item.get("category") or ""
        slot = slot_of(category)
        if slot is None or slot in outfit:
            continue
        meta = item.get("meta_data") or {}
        outfit[slot] = {"color": meta.get("color") or "", "category": category,
                        "crop_key": meta.get("crop_key")}
    return outfit
