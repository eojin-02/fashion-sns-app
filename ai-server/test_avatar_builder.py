"""avatar_builder 단위 테스트 — 인프라 없이 실행: python test_avatar_builder.py"""
import io

import trimesh

import avatar_builder


def test_glb_is_valid_and_loadable():
    glb = avatar_builder.build_avatar_glb(
        {"top": "블랙", "bottom": "청바지", "shoes": "화이트",
         "outer": "카키", "accessory": "레드"})
    assert glb[:4] == b"glTF", "GLB 매직 바이트"
    scene = trimesh.load(io.BytesIO(glb), file_type="glb")
    names = set(scene.geometry)
    # 풀 착장이면 아우터 셸과 캡까지 포함된다
    for expected in ("torso", "leg-l", "leg-r", "head", "outer", "cap"):
        assert expected in names, f"{expected} 누락: {names}"


def test_bare_outfit_omits_optional_parts():
    scene = trimesh.load(
        io.BytesIO(avatar_builder.build_avatar_glb({})), file_type="glb")
    names = set(scene.geometry)
    assert "outer" not in names and "cap" not in names
    assert "torso" in names  # 태그가 없어도 기본 색으로 완전한 몸체는 생성


def test_color_matching_korean_and_english():
    assert avatar_builder.color_of("블랙") == (0.12, 0.12, 0.12)
    assert avatar_builder.color_of("Light Blue") == (0.20, 0.35, 0.70)
    assert avatar_builder.color_of("형광 연두") == (0.60, 0.60, 0.65)  # 미지정 → 기본색
    assert avatar_builder.color_of(None) == (0.60, 0.60, 0.65)


def test_category_slot_matching():
    assert avatar_builder.slot_of("상의") == "top"
    assert avatar_builder.slot_of("아우터") == "outer"
    assert avatar_builder.slot_of("가죽 자켓") == "outer"
    assert avatar_builder.slot_of("신발") == "shoes"
    assert avatar_builder.slot_of("가방") is None


def test_config_resolution_and_fallbacks():
    skin, hair, style = avatar_builder.resolve_config(
        {"skin": "deep", "hair_color": "pink", "hair_style": "long"})
    assert skin == avatar_builder.SKIN_TONES["deep"]
    assert hair == avatar_builder.HAIR_COLORS["pink"]
    assert style == "long"
    # 미지정/오타/None은 전부 기본값으로 — 워커가 절대 실패하지 않아야 한다
    assert avatar_builder.resolve_config(None) == (
        avatar_builder.SKIN_TONES["light"], avatar_builder.HAIR_COLORS["black"], "short")
    assert avatar_builder.resolve_config(
        {"skin": "??", "hair_style": "mohawk"})[2] == "short"


def test_hair_precedence_manual_over_detected_over_default():
    # "auto" → 사진 감지값(detected_*)을 따른다
    _, hair, style = avatar_builder.resolve_config({
        "hair_style": "auto", "hair_color": "auto",
        "detected_hair_style": "long", "detected_hair_color": "brown"})
    assert style == "long" and hair == avatar_builder.HAIR_COLORS["brown"]
    # 수동 선택이 있으면 감지값을 무시한다
    _, hair, style = avatar_builder.resolve_config({
        "hair_style": "bald", "hair_color": "pink",
        "detected_hair_style": "long", "detected_hair_color": "brown"})
    assert style == "bald" and hair == avatar_builder.HAIR_COLORS["pink"]
    # 감지값이 쓰레기면 기본값
    _, hair, style = avatar_builder.resolve_config({
        "hair_style": "auto", "detected_hair_style": "afro-mullet"})
    assert style == "short"


def test_bald_style_omits_hair_geometry():
    scene = trimesh.load(
        io.BytesIO(avatar_builder.build_avatar_glb({}, {"hair_style": "bald"})),
        file_type="glb")
    names = set(scene.geometry)
    assert "hair" not in names and "hair-back" not in names
    assert "head" in names


def test_outfit_from_items_keeps_first_per_slot():
    outfit = avatar_builder.outfit_from_items([
        {"category": "상의", "meta_data": {"color": "블랙"}},
        {"category": "상의", "meta_data": {"color": "화이트"}},  # 같은 슬롯 — 무시
        {"category": "하의", "meta_data": None},
    ])
    assert outfit == {"top": "블랙", "bottom": ""}


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"PASS {name}")
    print("모든 테스트 통과")
