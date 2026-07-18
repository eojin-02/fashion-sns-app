"""best.pt → ONNX 변환 + 라벨 파일 생성 (DESIGN.md 4절).

실행 예:
    python export.py --checkpoint runs/best.pt --labels ./data/labels.json \
        --out-dir ../models

산출:
    ../models/clothes_tagger.onnx         — 워커가 onnxruntime으로 로드
    ../models/clothes_tagger.labels.json  — {"version", "classes", "slot_of"}

변환 후 onnxruntime으로 재로드해 PyTorch 출력과 일치하는지 스모크 테스트한다.
"""
import argparse
import json
from pathlib import Path

import torch
from torch import nn
from torchvision.models import mobilenet_v3_large


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--labels", required=True, type=Path,
                        help="prepare_data.py가 만든 labels.json")
    parser.add_argument("--out-dir", default=Path("../models"), type=Path)
    args = parser.parse_args()

    checkpoint = torch.load(args.checkpoint, map_location="cpu")
    classes = checkpoint["classes"]

    model = mobilenet_v3_large()
    model.classifier[-1] = nn.Linear(model.classifier[-1].in_features, len(classes))
    model.load_state_dict(checkpoint["state_dict"])
    model.eval()

    args.out_dir.mkdir(parents=True, exist_ok=True)
    onnx_path = args.out_dir / "clothes_tagger.onnx"
    dummy = torch.randn(1, 3, 224, 224)
    torch.onnx.export(
        model, dummy, onnx_path,
        input_names=["image"], output_names=["logits"],
        dynamic_axes={"image": {0: "batch"}, "logits": {0: "batch"}},
        opset_version=17,
    )

    meta = json.loads(args.labels.read_text(encoding="utf-8"))
    labels_path = args.out_dir / "clothes_tagger.labels.json"
    labels_path.write_text(
        json.dumps({"version": meta.get("version", "v1"),
                    "classes": classes,
                    "slot_of": meta["slot_of"]}, ensure_ascii=False, indent=2),
        encoding="utf-8")

    # 스모크 테스트 — PyTorch와 ONNX의 argmax가 일치해야 한다
    import numpy as np
    import onnxruntime as ort

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    sample = torch.randn(2, 3, 224, 224)
    with torch.no_grad():
        torch_pred = model(sample).argmax(dim=1).tolist()
    onnx_logits = session.run(None, {"image": sample.numpy().astype(np.float32)})[0]
    onnx_pred = onnx_logits.argmax(axis=1).tolist()
    assert torch_pred == onnx_pred, f"불일치: torch={torch_pred} onnx={onnx_pred}"

    size_mb = onnx_path.stat().st_size / 1024 / 1024
    print(f"OK — {onnx_path} ({size_mb:.1f}MB), 클래스 {len(classes)}개")
    print(f"라벨: {labels_path}")
    print("스모크 테스트 통과 (torch/onnx argmax 일치)")


if __name__ == "__main__":
    main()
