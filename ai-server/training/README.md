# 의류 태깅 모델 학습 가이드 (GPU 컴퓨터에서 실행)

배경/설계 근거는 [DESIGN.md](DESIGN.md) 참고. 이 문서는 실행 순서만 담는다.

## 0. 준비 (최초 1회)

```bash
git clone https://github.com/eojin-02/fashion-sns-app.git
cd fashion-sns-app/ai-server/training

# 가상환경 권장
python -m venv .venv
.venv\Scripts\activate          # (Windows) / source .venv/bin/activate (Linux)

# GPU면 pytorch.org에서 CUDA 맞는 명령으로 torch 먼저 설치 후:
pip install -r requirements.txt

# GPU 인식 확인 — True 나와야 GPU 학습
python -c "import torch; print(torch.cuda.is_available())"
```

## 1. 데이터셋 다운로드 (Kaggle)

[Fashion Product Images (Small)](https://www.kaggle.com/datasets/paramaggarwal/fashion-product-images-small)
— Kaggle 로그인 후 Download (~600MB), 또는 kaggle CLI:

```bash
kaggle datasets download paramaggarwal/fashion-product-images-small
```

압축을 풀면 `styles.csv`와 `images/` 폴더가 나온다. 그 폴더 경로를 아래에서 사용.

## 2. 데이터 준비 → 학습 → 변환

```bash
# ① 라벨 매핑 + 8:1:1 스플릿 (클래스 분포·병합 로그를 확인할 것)
python prepare_data.py --dataset-dir <데이터셋폴더> --out-dir ./data

# ② 스모크 테스트 먼저 — 1~2분 안에 끝나면 환경 OK
python train.py --data-dir ./data --images-root <데이터셋폴더> \
    --limit 200 --head-epochs 1 --finetune-epochs 0 --batch-size 16

# ③ 본 학습 (T4급 GPU ~1시간). 종료 시 test 리포트 출력:
#    세부 macro-F1 ≥ 0.85, 슬롯 macro-F1 ≥ 0.92 가 목표
python train.py --data-dir ./data --images-root <데이터셋폴더>

# ④ ONNX 변환 (+ torch/onnx 일치 스모크 테스트 자동 실행)
python export.py --checkpoint runs/best.pt --labels ./data/labels.json --out-dir ../models
```

## 3. 결과물 전달

`ai-server/models/` 아래 두 파일이 산출물이다:

- `clothes_tagger.onnx` (~15MB)
- `clothes_tagger.labels.json`

v1은 그냥 커밋해서 푸시하면 된다 (모델 업데이트가 잦아지면 그때 Git LFS 전환).
워커 통합(`local_tagger.py`)은 이 파일이 존재하면 자동으로 사용하는 구조로 만든다.

## 문제 해결

- `CUDA out of memory` → `--batch-size 32` 로 낮추기
- 학습이 너무 느림 (GPU 미사용) → 0단계의 `torch.cuda.is_available()` 확인,
  CUDA 버전에 맞는 torch 재설치
- Windows에서 DataLoader 멈춤 → `--workers 0` 추가
