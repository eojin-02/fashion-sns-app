"""worker.crop_texture_bytes 단위 테스트 — 인프라 없이 실행: python test_worker_crop.py"""
import io

from PIL import Image

import worker


def _sample_jpeg() -> bytes:
    img = Image.new("RGB", (200, 400), (10, 10, 10))
    # 하단 절반을 빨간색으로 — 크롭 위치 검증용
    img.paste((220, 30, 30), (0, 200, 200, 400))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return buf.getvalue()


def test_valid_box_crops_expected_region():
    crop = worker.crop_texture_bytes(_sample_jpeg(), [0.0, 0.5, 1.0, 1.0])
    assert crop is not None
    img = Image.open(io.BytesIO(crop))
    r, g, b = img.resize((1, 1)).getpixel((0, 0))
    assert r > 150 and g < 100  # 하단(빨강) 영역이 잘렸는지


def test_invalid_boxes_fall_back_to_none():
    jpeg = _sample_jpeg()
    assert worker.crop_texture_bytes(jpeg, None) is None
    assert worker.crop_texture_bytes(jpeg, [0.1, 0.1]) is None          # 좌표 부족
    assert worker.crop_texture_bytes(jpeg, ["a", 0, 1, 1]) is None      # 숫자 아님
    assert worker.crop_texture_bytes(jpeg, [0.5, 0.5, 0.51, 0.51]) is None  # 너무 작음


def test_thousand_scale_box_is_normalized():
    # 모델이 0~1000 스케일로 답하는 경우 — 하단 절반(빨강)을 정상 크롭해야 한다
    crop = worker.crop_texture_bytes(_sample_jpeg(), [0, 500, 1000, 1000])
    assert crop is not None
    img = Image.open(io.BytesIO(crop))
    r, g, b = img.resize((1, 1)).getpixel((0, 0))
    assert r > 150 and g < 100


def test_mixed_scale_box_is_normalized_per_value():
    # 실측 사례: x는 0~1 소수, y는 0~1000 정수로 섞여 오는 응답
    crop = worker.crop_photo_bytes(_sample_jpeg(), [0.0, 500, 1.0, 1000])
    assert crop is not None
    img = Image.open(io.BytesIO(crop))
    r, g, b = img.resize((1, 1)).getpixel((0, 0))
    assert r > 150 and g < 100


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"PASS {name}")
    print("모든 테스트 통과")
