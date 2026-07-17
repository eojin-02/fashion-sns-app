"""절차적 3D 아바타 생성 (설계서 4.2 avatar_bundle_url).

Vision 태깅 결과(카테고리/색상)만으로 블록 스타일 캐릭터 GLB를 만든다.
실사 신체를 3D화하지 않으므로 행인 보호·익명성 원칙(설계서 1.2/2.2)과 충돌이 없고,
생성 비용이 0이라 스캔 때마다 즉시 재생성할 수 있다.
앱은 model-viewer 계열 뷰어로 GLB를 로드해 360° 회전(앞뒤 양옆)으로 보여준다.
"""
import trimesh

# 아바타 베이스 파라미터 (users.avatar_config) — 가입 시 1회 선택, 이후 변경 가능.
# 값이 없거나 모르는 값이면 첫 번째 항목으로 폴백해 절대 실패하지 않는다.
SKIN_TONES = {
    "light": (0.96, 0.80, 0.69),
    "tan": (0.80, 0.60, 0.45),
    "deep": (0.45, 0.30, 0.22),
}
HAIR_COLORS = {
    "black": (0.10, 0.10, 0.10),
    "brown": (0.35, 0.22, 0.12),
    "blonde": (0.85, 0.72, 0.45),
    "red": (0.55, 0.20, 0.12),
    "blue": (0.25, 0.35, 0.75),
    "pink": (0.90, 0.55, 0.65),
}
HAIR_STYLES = ("short", "long", "bald")

_DEFAULT_CLOTH = (0.60, 0.60, 0.65)

# Gemini가 뱉는 색상 문자열(한/영 혼용) → RGB. 부분 문자열 매칭.
_COLOR_MAP = [
    (("검정", "블랙", "black"), (0.12, 0.12, 0.12)),
    (("흰", "화이트", "아이보리", "white", "ivory"), (0.93, 0.93, 0.93)),
    (("회색", "그레이", "gray", "grey"), (0.55, 0.55, 0.55)),
    (("네이비", "navy"), (0.08, 0.12, 0.30)),
    (("청", "데님", "denim", "파랑", "파란", "블루", "blue"), (0.20, 0.35, 0.70)),
    (("빨강", "빨간", "레드", "red"), (0.75, 0.15, 0.15)),
    (("카키", "올리브", "khaki", "olive"), (0.40, 0.40, 0.25)),
    (("초록", "그린", "green"), (0.15, 0.50, 0.25)),
    (("베이지", "크림", "beige", "cream", "tan"), (0.85, 0.78, 0.62)),
    (("갈색", "브라운", "brown"), (0.42, 0.28, 0.18)),
    (("노랑", "노란", "옐로", "yellow"), (0.90, 0.78, 0.20)),
    (("핑크", "분홍", "pink"), (0.92, 0.60, 0.70)),
    (("보라", "퍼플", "purple"), (0.50, 0.30, 0.60)),
    (("주황", "오렌지", "orange"), (0.90, 0.50, 0.15)),
]

# 자유 텍스트 카테고리 → 표준 슬롯. Gemini 프롬프트는 5종 고정이지만 방어적으로 매칭.
_CATEGORY_KEYWORDS = [
    ("outer", ("아우터", "자켓", "재킷", "코트", "점퍼", "패딩", "outer", "jacket", "coat")),
    ("top", ("상의", "셔츠", "티", "니트", "후드", "top", "shirt", "tee", "hoodie")),
    ("bottom", ("하의", "바지", "팬츠", "스커트", "진", "bottom", "pants", "skirt", "jeans")),
    ("shoes", ("신발", "슈즈", "스니커", "부츠", "shoes", "sneaker", "boots")),
    ("accessory", ("액세서리", "모자", "캡", "acc", "hat", "cap")),
]


def color_of(text: str | None) -> tuple:
    if text:
        lowered = text.lower()
        for keywords, rgb in _COLOR_MAP:
            if any(k in lowered for k in keywords):
                return rgb
    return _DEFAULT_CLOTH


def resolve_config(config: dict | None) -> tuple:
    """avatar_config → (피부 RGB, 헤어 RGB, 헤어스타일). 미지정/오타는 기본값 폴백."""
    config = config or {}
    skin = SKIN_TONES.get(config.get("skin"), SKIN_TONES["light"])
    hair_color = HAIR_COLORS.get(config.get("hair_color"), HAIR_COLORS["black"])
    style = config.get("hair_style")
    if style not in HAIR_STYLES:
        style = "short"
    return skin, hair_color, style


def slot_of(category: str | None) -> str | None:
    if not category:
        return None
    lowered = category.lower()
    for slot, keywords in _CATEGORY_KEYWORDS:
        if any(k in lowered for k in keywords):
            return slot
    return None


def _box(name: str, scene: trimesh.Scene, size, center, rgb) -> None:
    mesh = trimesh.creation.box(extents=size)
    mesh.apply_translation(center)
    mesh.visual = trimesh.visual.TextureVisuals(
        material=trimesh.visual.material.PBRMaterial(
            name=f"{name}-mat",
            baseColorFactor=[*rgb, 1.0],
            metallicFactor=0.0,
            roughnessFactor=0.9))
    scene.add_geometry(mesh, node_name=name, geom_name=name)


def build_avatar_glb(outfit: dict, config: dict | None = None) -> bytes:
    """outfit: {"top": "블랙", ...} 슬롯별 색상 / config: 베이스 파라미터(피부·헤어).

    Y-up, 바닥 y=0, 신장 약 1.4 유닛의 블록 캐릭터를 GLB 바이트로 반환한다.
    """
    skin, hair_color, hair_style = resolve_config(config)
    top = color_of(outfit.get("top"))
    bottom = color_of(outfit.get("bottom"))
    shoes = color_of(outfit.get("shoes"))

    scene = trimesh.Scene()

    # 하체: 발(신발) → 다리(하의)
    for side, x in (("l", -0.09), ("r", 0.09)):
        _box(f"foot-{side}", scene, (0.14, 0.10, 0.24), (x, 0.05, 0.04), shoes)
        _box(f"leg-{side}", scene, (0.15, 0.55, 0.16), (x, 0.375, 0.0), bottom)

    # 상체(상의) + 팔
    _box("torso", scene, (0.36, 0.50, 0.20), (0.0, 0.90, 0.0), top)
    for side, x in (("l", -0.23), ("r", 0.23)):
        _box(f"arm-{side}", scene, (0.10, 0.48, 0.10), (x, 0.89, 0.0), top)

    # 아우터: 상체를 감싸는 살짝 큰 셸 (입었을 때만)
    if outfit.get("outer"):
        outer = color_of(outfit.get("outer"))
        _box("outer", scene, (0.44, 0.46, 0.28), (0.0, 0.92, 0.0), outer)
        _box("collar", scene, (0.30, 0.06, 0.30), (0.0, 1.13, 0.0), outer)

    # 머리 + 머리카락 (베이스 파라미터 반영)
    _box("head", scene, (0.24, 0.24, 0.24), (0.0, 1.29, 0.0), skin)
    if hair_style != "bald":
        _box("hair", scene, (0.26, 0.08, 0.26), (0.0, 1.43, 0.0), hair_color)
        back = (0.26, 0.36, 0.06) if hair_style == "long" else (0.26, 0.16, 0.06)
        back_y = 1.21 if hair_style == "long" else 1.31
        _box("hair-back", scene, back, (0.0, back_y, -0.11), hair_color)

    # 액세서리(모자 등): 머리 위 캡
    if outfit.get("accessory"):
        acc = color_of(outfit.get("accessory"))
        _box("cap", scene, (0.28, 0.07, 0.28), (0.0, 1.49, 0.0), acc)
        _box("cap-brim", scene, (0.26, 0.03, 0.14), (0.0, 1.47, 0.19), acc)

    return scene.export(file_type="glb")


def outfit_from_items(items: list[dict]) -> dict:
    """clothes_item 행 목록 → 슬롯별 색상. 같은 슬롯이면 먼저 온 것(최신) 우선."""
    outfit: dict = {}
    for item in items:
        slot = slot_of(item.get("category"))
        if slot is None or slot in outfit:
            continue
        meta = item.get("meta_data") or {}
        outfit[slot] = meta.get("color") or ""
    return outfit
