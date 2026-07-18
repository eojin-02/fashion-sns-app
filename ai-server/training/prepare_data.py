"""Kaggle Fashion Product Images → 세부 카테고리 학습 데이터셋 (DESIGN.md 1.1/2절).

입력: 압축 해제한 데이터셋 폴더 (styles.csv + images/*.jpg)
출력: --out-dir 에 labels.json / train.csv / val.csv / test.csv

실행 예:
    python prepare_data.py --dataset-dir ./fashion-dataset --out-dir ./data

articleType(영문)을 세부 클래스(한국어)로 매핑하고, 표본이 --min-samples 미만인
클래스는 슬롯 이름("상의" 등)으로 병합한다. 매핑에 없는 articleType(속옷·향수 등)은
버리고 개수만 로그로 남긴다. 외부 의존성 없음(표준 라이브러리만).
"""
import argparse
import csv
import json
import random
from collections import Counter, defaultdict
from pathlib import Path

# articleType → (세부 클래스, 슬롯). DESIGN.md 1.1 초안 기준.
ARTICLE_TO_FINE = {
    # 상의
    "Tshirts": ("티셔츠", "상의"),
    "Tops": ("티셔츠", "상의"),
    "Shirts": ("셔츠·블라우스", "상의"),
    "Tunics": ("셔츠·블라우스", "상의"),
    "Sweaters": ("니트", "상의"),
    "Sweatshirts": ("후드·맨투맨", "상의"),
    # 하의
    "Jeans": ("청바지", "하의"),
    "Trousers": ("슬랙스", "하의"),
    "Shorts": ("반바지", "하의"),
    "Skirts": ("스커트", "하의"),
    "Leggings": ("레깅스·트레이닝", "하의"),
    "Track Pants": ("레깅스·트레이닝", "하의"),
    "Tights": ("레깅스·트레이닝", "하의"),
    # 아우터
    "Jackets": ("자켓·블레이저", "아우터"),
    "Blazers": ("자켓·블레이저", "아우터"),
    "Rain Jacket": ("자켓·블레이저", "아우터"),
    "Waistcoat": ("자켓·블레이저", "아우터"),
    "Coats": ("코트", "아우터"),
    "Cardigan": ("집업·가디건", "아우터"),
    "Shrug": ("집업·가디건", "아우터"),
    # 원피스 — v1 슬롯은 상의 (3단계 형태 다양화에서 통짜 처리)
    "Dresses": ("원피스", "상의"),
    "Jumpsuit": ("원피스", "상의"),
    # 신발
    "Casual Shoes": ("스니커즈", "신발"),
    "Sports Shoes": ("스니커즈", "신발"),
    "Heels": ("구두·힐", "신발"),
    "Flats": ("구두·힐", "신발"),
    "Formal Shoes": ("구두·힐", "신발"),
    "Boots": ("부츠", "신발"),
    "Sandals": ("샌들·슬리퍼", "신발"),
    "Sports Sandals": ("샌들·슬리퍼", "신발"),
    "Flip Flops": ("샌들·슬리퍼", "신발"),
    # 액세서리
    "Caps": ("모자", "액세서리"),
    "Hat": ("모자", "액세서리"),
    "Handbags": ("가방", "액세서리"),
    "Backpacks": ("가방", "액세서리"),
    "Clutches": ("가방", "액세서리"),
    "Messenger Bag": ("가방", "액세서리"),
    "Duffel Bag": ("가방", "액세서리"),
    "Watches": ("기타 액세서리", "액세서리"),
    "Belts": ("기타 액세서리", "액세서리"),
    "Sunglasses": ("기타 액세서리", "액세서리"),
    "Ties": ("기타 액세서리", "액세서리"),
    "Scarves": ("기타 액세서리", "액세서리"),
}

SPLIT_RATIOS = (0.8, 0.1, 0.1)  # train / val / test


def load_rows(dataset_dir: Path) -> list:
    """styles.csv 로드. 마지막 열(상품명)에 콤마가 섞인 불량 행이 있어
    필요한 앞쪽 열(id, articleType)만 취한다."""
    rows = []
    with open(dataset_dir / "styles.csv", encoding="utf-8", errors="replace") as f:
        reader = csv.reader(f)
        header = next(reader)
        id_i, article_i = header.index("id"), header.index("articleType")
        for row in reader:
            if len(row) > max(id_i, article_i):
                rows.append((row[id_i].strip(), row[article_i].strip()))
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", required=True, type=Path)
    parser.add_argument("--out-dir", default=Path("data"), type=Path)
    parser.add_argument("--min-samples", default=500, type=int,
                        help="이 미만인 세부 클래스는 슬롯 이름으로 병합")
    parser.add_argument("--seed", default=42, type=int)
    args = parser.parse_args()

    images_dir = args.dataset_dir / "images"
    samples = []          # (이미지 상대경로, 세부클래스)
    dropped = Counter()   # 매핑 밖 articleType
    missing_files = 0

    for image_id, article in load_rows(args.dataset_dir):
        mapped = ARTICLE_TO_FINE.get(article)
        if mapped is None:
            dropped[article] += 1
            continue
        if not (images_dir / f"{image_id}.jpg").is_file():
            missing_files += 1
            continue
        samples.append((f"images/{image_id}.jpg", mapped[0]))

    # 소수 클래스 병합: 세부 → 슬롯 이름
    fine_to_slot = {fine: slot for fine, slot in ARTICLE_TO_FINE.values()}
    counts = Counter(label for _, label in samples)
    merged = {}
    for fine, n in sorted(counts.items()):
        if n < args.min_samples:
            merged[fine] = fine_to_slot[fine]
            print(f"[병합] {fine}: {n}장 < {args.min_samples} → '{fine_to_slot[fine]}'")
    samples = [(p, merged.get(label, label)) for p, label in samples]

    # 최종 클래스 목록 + 슬롯 매핑 (병합으로 생긴 슬롯 이름 클래스 포함)
    classes = sorted({label for _, label in samples})
    slot_of = {c: fine_to_slot.get(c, c) for c in classes}

    print("\n=== 최종 클래스 분포 ===")
    for label, n in Counter(l for _, l in samples).most_common():
        print(f"  {label:12s} {n:6d}장  (슬롯: {slot_of[label]})")
    print(f"학습 제외(매핑 밖): {sum(dropped.values())}장 "
          f"(상위: {dropped.most_common(5)})")
    print(f"이미지 파일 누락: {missing_files}장")

    # 클래스별 stratified 8:1:1 스플릿
    rng = random.Random(args.seed)
    by_class = defaultdict(list)
    for path, label in samples:
        by_class[label].append(path)
    splits = {"train": [], "val": [], "test": []}
    for label, paths in sorted(by_class.items()):
        rng.shuffle(paths)
        n_train = int(len(paths) * SPLIT_RATIOS[0])
        n_val = int(len(paths) * SPLIT_RATIOS[1])
        splits["train"] += [(p, label) for p in paths[:n_train]]
        splits["val"] += [(p, label) for p in paths[n_train:n_train + n_val]]
        splits["test"] += [(p, label) for p in paths[n_train + n_val:]]

    args.out_dir.mkdir(parents=True, exist_ok=True)
    for name, rows in splits.items():
        rng.shuffle(rows)
        with open(args.out_dir / f"{name}.csv", "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["image", "label"])
            writer.writerows(rows)
        print(f"{name}.csv: {len(rows)}장")

    with open(args.out_dir / "labels.json", "w", encoding="utf-8") as f:
        json.dump({"version": "v1", "classes": classes, "slot_of": slot_of},
                  f, ensure_ascii=False, indent=2)
    print(f"labels.json: {len(classes)}개 클래스 → {args.out_dir}")


if __name__ == "__main__":
    main()
