"""세부 의류 카테고리 분류 — MobileNetV3-Large 2단계 전이학습 (DESIGN.md 3절).

실행 예 (GPU 자동 감지):
    python train.py --data-dir ./data --images-root ./fashion-dataset
스모크 테스트 (CPU, 소량):
    python train.py --data-dir ./data --images-root ./fashion-dataset \
        --limit 200 --head-epochs 1 --finetune-epochs 0 --batch-size 16

산출: --out-dir/best.pt — val macro-F1 최고 시점의 가중치 + 클래스 목록.
학습 종료 시 test 스플릿의 세부/슬롯 macro-F1을 리포트한다 (목표: 0.85 / 0.92).
"""
import argparse
import csv
import json
import random
from collections import defaultdict
from pathlib import Path

import torch
from PIL import Image
from torch import nn
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler
from torchvision import transforms
from torchvision.models import MobileNet_V3_Large_Weights, mobilenet_v3_large

IMAGENET_MEAN = [0.485, 0.456, 0.406]
IMAGENET_STD = [0.229, 0.224, 0.225]


class ClothesCsvDataset(Dataset):
    def __init__(self, csv_path: Path, images_root: Path, classes: list,
                 transform, limit: int = 0):
        self.samples = []
        with open(csv_path, encoding="utf-8") as f:
            for row in csv.DictReader(f):
                self.samples.append((images_root / row["image"], row["label"]))
        if limit:
            self.samples = self.samples[:limit]
        self.class_index = {c: i for i, c in enumerate(classes)}
        self.transform = transform

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, i):
        path, label = self.samples[i]
        image = Image.open(path).convert("RGB")
        return self.transform(image), self.class_index[label]


def macro_f1(confusion: dict, classes: list) -> float:
    """confusion[(정답, 예측)] = 개수 → macro-F1. sklearn 의존 회피."""
    scores = []
    for i, _ in enumerate(classes):
        tp = confusion.get((i, i), 0)
        fp = sum(v for (t, p), v in confusion.items() if p == i and t != i)
        fn = sum(v for (t, p), v in confusion.items() if t == i and p != i)
        denom = 2 * tp + fp + fn
        scores.append(2 * tp / denom if denom else 0.0)
    return sum(scores) / len(scores)


@torch.no_grad()
def evaluate(model, loader, device) -> dict:
    model.eval()
    confusion = defaultdict(int)
    for images, targets in loader:
        preds = model(images.to(device)).argmax(dim=1).cpu()
        for t, p in zip(targets.tolist(), preds.tolist()):
            confusion[(t, p)] += 1
    return confusion


def fold_to_slots(confusion: dict, classes: list, slot_of: dict) -> tuple:
    """세부 혼동행렬을 슬롯 단위로 접는다 — 세부가 틀려도 슬롯이 맞으면 정답."""
    slots = sorted(set(slot_of.values()))
    slot_index = {s: i for i, s in enumerate(slots)}
    folded = defaultdict(int)
    for (t, p), n in confusion.items():
        folded[(slot_index[slot_of[classes[t]]], slot_index[slot_of[classes[p]]])] += n
    return folded, slots


def train_epochs(model, loader, val_loader, device, classes, *,
                 epochs, lr, best_f1, out_path, scheduler_total=None):
    criterion = nn.CrossEntropyLoss(label_smoothing=0.1)
    params = [p for p in model.parameters() if p.requires_grad]
    optimizer = torch.optim.AdamW(params, lr=lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        optimizer, T_max=scheduler_total or max(epochs, 1))
    scaler = torch.amp.GradScaler(enabled=device.type == "cuda")

    for epoch in range(epochs):
        model.train()
        total_loss = 0.0
        for images, targets in loader:
            images, targets = images.to(device), targets.to(device)
            optimizer.zero_grad(set_to_none=True)
            with torch.amp.autocast(device.type, enabled=device.type == "cuda"):
                loss = criterion(model(images), targets)
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            total_loss += loss.item() * images.size(0)
        scheduler.step()

        f1 = macro_f1(evaluate(model, val_loader, device), classes)
        avg_loss = total_loss / max(len(loader.dataset), 1)
        print(f"  epoch {epoch + 1}/{epochs}  loss={avg_loss:.4f}  val macro-F1={f1:.4f}")
        if f1 > best_f1:
            best_f1 = f1
            torch.save({"state_dict": model.state_dict(), "classes": classes}, out_path)
            print(f"  ↳ best 갱신 → {out_path}")
    return best_f1


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", required=True, type=Path,
                        help="prepare_data.py 산출물 폴더 (labels.json, *.csv)")
    parser.add_argument("--images-root", required=True, type=Path,
                        help="csv의 image 경로 기준 루트 (데이터셋 폴더)")
    parser.add_argument("--out-dir", default=Path("runs"), type=Path)
    parser.add_argument("--batch-size", default=64, type=int)
    parser.add_argument("--head-epochs", default=3, type=int)
    parser.add_argument("--finetune-epochs", default=7, type=int)
    parser.add_argument("--workers", default=4, type=int)
    parser.add_argument("--limit", default=0, type=int,
                        help="스모크 테스트용 — 스플릿당 표본 상한")
    parser.add_argument("--seed", default=42, type=int)
    args = parser.parse_args()

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"device: {device}")

    meta = json.loads((args.data_dir / "labels.json").read_text(encoding="utf-8"))
    classes, slot_of = meta["classes"], meta["slot_of"]

    train_tf = transforms.Compose([
        transforms.RandomResizedCrop(224, scale=(0.7, 1.0)),
        transforms.RandomHorizontalFlip(),
        transforms.ColorJitter(brightness=0.15, contrast=0.15),
        transforms.ToTensor(),
        transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
    ])
    eval_tf = transforms.Compose([
        transforms.Resize(256),
        transforms.CenterCrop(224),
        transforms.ToTensor(),
        transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
    ])

    datasets = {
        name: ClothesCsvDataset(args.data_dir / f"{name}.csv", args.images_root,
                                classes, tf, limit=args.limit)
        for name, tf in (("train", train_tf), ("val", eval_tf), ("test", eval_tf))
    }
    # 클래스 불균형 보정 (DESIGN.md 2.3)
    label_counts = defaultdict(int)
    for _, label in datasets["train"].samples:
        label_counts[label] += 1
    weights = [1.0 / label_counts[label] for _, label in datasets["train"].samples]
    sampler = WeightedRandomSampler(weights, num_samples=len(weights))

    loaders = {
        "train": DataLoader(datasets["train"], batch_size=args.batch_size,
                            sampler=sampler, num_workers=args.workers, pin_memory=True),
        "val": DataLoader(datasets["val"], batch_size=args.batch_size,
                          num_workers=args.workers),
        "test": DataLoader(datasets["test"], batch_size=args.batch_size,
                           num_workers=args.workers),
    }
    print({name: len(ds) for name, ds in datasets.items()})

    model = mobilenet_v3_large(weights=MobileNet_V3_Large_Weights.IMAGENET1K_V2)
    model.classifier[-1] = nn.Linear(model.classifier[-1].in_features, len(classes))
    model.to(device)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    best_path = args.out_dir / "best.pt"
    best_f1 = 0.0

    # 1단계: 백본 동결, 헤드만
    print("=== 1단계: 헤드 학습 (백본 동결) ===")
    for p in model.features.parameters():
        p.requires_grad = False
    best_f1 = train_epochs(model, loaders["train"], loaders["val"], device, classes,
                           epochs=args.head_epochs, lr=1e-3,
                           best_f1=best_f1, out_path=best_path)

    # 2단계: 마지막 블록 해동
    if args.finetune_epochs > 0:
        print("=== 2단계: 마지막 블록 파인튜닝 ===")
        for p in model.features[-4:].parameters():
            p.requires_grad = True
        best_f1 = train_epochs(model, loaders["train"], loaders["val"], device, classes,
                               epochs=args.finetune_epochs, lr=1e-4,
                               best_f1=best_f1, out_path=best_path)

    # 최종 리포트 — best 가중치로 test 평가
    model.load_state_dict(torch.load(best_path, map_location=device)["state_dict"])
    confusion = evaluate(model, loaders["test"], device)
    fine_f1 = macro_f1(confusion, classes)
    folded, slots = fold_to_slots(confusion, classes, slot_of)
    slot_f1 = macro_f1(folded, slots)
    print("\n=== test 리포트 ===")
    print(f"세부 macro-F1: {fine_f1:.4f}  (목표 ≥ 0.85)")
    print(f"슬롯 macro-F1: {slot_f1:.4f}  (목표 ≥ 0.92)")


if __name__ == "__main__":
    main()
