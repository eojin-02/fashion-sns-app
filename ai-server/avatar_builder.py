"""절차적 3D 아바타 생성 v3 — 복셀(Voxel) 스타일 (설계서 4.2 avatar_bundle_url).

Vision 태깅 결과(카테고리/색상)만으로 캐릭터 GLB를 만든다.
실사 신체를 3D화하지 않으므로 행인 보호·익명성 원칙(설계서 1.2/2.2)과 충돌이 없고,
생성 비용이 0이라 스캔 때마다 즉시 재생성할 수 있다.
앱은 model-viewer 계열 뷰어로 GLB를 로드해 360° 회전(앞뒤 양옆)으로 보여준다.

v3: 마인크래프트풍 각진 복셀 몸체 + 픽셀화된 사진 텍스처(정면 투영 UV) 조합 —
착장을 미러링하는 복셀 캐릭터 컨셉. 카테고리별 형태 분기(스커트/반바지/후드) 유지.
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
def _vox(name: str, scene: trimesh.Scene, size, center, rgb,
         texture=None) -> None:
    """복셀 파트의 기본 단위 — 각진 박스.

    texture(PIL.Image)가 있으면 정면(+z) 평면 투영 UV로 래핑한다 — 픽셀화된
    사진 크롭이 마인크래프트 스킨처럼 발린다. 옆면은 가장자리 픽셀이 늘어나는데,
    복셀 스타일에선 그게 자연스러운 룩이다.
    """
    mesh = trimesh.creation.box(extents=size)
    if texture is not None:
        u = mesh.vertices[:, 0] / size[0] + 0.5
        v = mesh.vertices[:, 1] / size[1] + 0.5
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

    Y-up, 바닥 y=0, 약 4등신 복셀 비율(총 높이 약 1.3, 머리 0.28) —
    레퍼런스(복셀 캐릭터가 착장을 미러링)처럼 큰 머리 + 각진 몸체.
    """
    o = _norm_outfit(outfit)
    skin, hair_rgb, hair_style = resolve_config(config)
    top, bottom = o.get("top"), o.get("bottom")
    outer, shoes, acc = o.get("outer"), o.get("shoes"), o.get("accessory")

    top_rgb = color_of(top["color"] if top else None)
    bottom_rgb = color_of(bottom["color"] if bottom else None)
    shoes_rgb = color_of(shoes["color"] if shoes else None)
    # 픽셀화된 사진 크롭 텍스처 (없으면 단색) — 큰 파트에만 적용
    top_tex = top.get("texture") if top else None
    bottom_tex = bottom.get("texture") if bottom else None
    outer_tex = outer.get("texture") if outer else None

    # 카테고리 기반 형태 분기
    dress = _has_kw(top, "원피스", "드레스")
    skirt = dress or _has_kw(bottom, "스커트", "치마")
    shorts = not skirt and _has_kw(bottom, "반바지", "쇼츠")
    hood_src = outer if _has_kw(outer, "후드") else (top if _has_kw(top, "후드") else None)

    scene = trimesh.Scene()

    # 신발(0~0.09) + 다리(0.09~0.50)
    for side, x in (("l", -0.072), ("r", 0.072)):
        _vox(f"foot-{side}", scene, (0.125, 0.09, 0.20), (x, 0.045, 0.03), shoes_rgb)
        if skirt:
            _vox(f"leg-{side}", scene, (0.10, 0.41, 0.11), (x, 0.295, 0.0), skin)
        elif shorts:
            _vox(f"leg-{side}", scene, (0.125, 0.20, 0.135), (x, 0.40, 0.0),
                 bottom_rgb, texture=bottom_tex)
            _vox(f"leg-{side}-lower", scene, (0.095, 0.21, 0.105), (x, 0.195, 0.0), skin)
        else:
            _vox(f"leg-{side}", scene, (0.125, 0.41, 0.135), (x, 0.295, 0.0),
                 bottom_rgb, texture=bottom_tex)

    # 스커트 — 힙을 감싸는 넓은 박스 (원피스면 상의 색)
    if skirt:
        _vox("skirt", scene, (0.34, 0.18, 0.23), (0.0, 0.53, 0.0),
             top_rgb if dress else bottom_rgb,
             texture=top_tex if dress else bottom_tex)

    # 몸통(상의, 0.50~0.92) + 아우터 셸
    _vox("torso", scene, (0.30, 0.42, 0.165), (0.0, 0.71, 0.0), top_rgb,
         texture=top_tex)
    arm_rgb = top_rgb
    if outer:
        outer_rgb = color_of(outer["color"])
        _vox("outer", scene, (0.36, 0.44, 0.225), (0.0, 0.715, 0.0), outer_rgb,
             texture=outer_tex)
        _vox("collar", scene, (0.20, 0.05, 0.19), (0.0, 0.955, 0.0), outer_rgb)
        arm_rgb = outer_rgb  # 소매는 겉옷 색

    # 팔 + 손
    for side, x in (("l", -0.245), ("r", 0.245)):
        _vox(f"arm-{side}", scene, (0.09, 0.38, 0.11), (x, 0.72, 0.0), arm_rgb)
        _vox(f"hand-{side}", scene, (0.09, 0.09, 0.11), (x, 0.485, 0.0), skin)

    # 머리(0.93~1.21) + 얼굴 (눈·입)
    _vox("head", scene, (0.30, 0.28, 0.28), (0.0, 1.07, 0.0), skin)
    for side, x in (("l", -0.075), ("r", 0.075)):
        _vox(f"eye-{side}", scene, (0.045, 0.06, 0.02), (x, 1.09, 0.135), _EYE)
    _vox("mouth", scene, (0.05, 0.028, 0.02), (0.0, 0.995, 0.135), (0.85, 0.45, 0.45))

    # 헤어 — 윗머리 슬래브 + 뒤통수 슬래브 + 앞머리 스트립
    if hair_style != "bald":
        _vox("hair", scene, (0.32, 0.10, 0.30), (0.0, 1.245, -0.005), hair_rgb)
        _vox("hair-bang", scene, (0.31, 0.075, 0.035), (0.0, 1.185, 0.145), hair_rgb)
        back_size = (0.31, 0.42, 0.075) if hair_style == "long" else (0.31, 0.22, 0.075)
        back_y = 0.995 if hair_style == "long" else 1.095
        _vox("hair-back", scene, back_size, (0.0, back_y, -0.135), hair_rgb)

    # 후드 — 목 뒤의 각진 후드 박스
    if hood_src is not None:
        _vox("hood", scene, (0.24, 0.14, 0.10), (0.0, 0.92, -0.16),
             color_of(hood_src["color"]))

    # 액세서리(모자) — 챙 있는 캡 (헤어 슬래브 위)
    if acc:
        acc_rgb = color_of(acc["color"])
        _vox("cap", scene, (0.335, 0.09, 0.315), (0.0, 1.315, -0.005), acc_rgb)
        _vox("cap-brim", scene, (0.28, 0.03, 0.13), (0.0, 1.285, 0.215), acc_rgb)

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
