# 의류 태깅 자체 모델 파인튜닝 설계 (v1)

목표: [worker.py](../worker.py)의 태깅 폴백을 고정 스텁("상의")에서 **파인튜닝한 분류 모델**로
교체한다. Gemini는 유지하되, 키가 없거나 호출이 실패하면 로컬 모델이 대신 태깅한다.

```
분석 우선순위:  Gemini API  →  로컬 모델(ONNX)  →  스텁
                (정확, 유료·외부전송)  (무료, 프라이빗)   (최후 폴백)
```

출력 계약은 기존과 동일하게 유지한다 — 파이프라인/캐시/아바타 생성 무수정:

```json
{"category": "상의|하의|아우터|신발|액세서리",
 "brand_guess": null,
 "color": "블랙",
 "style_tags": []}
```

---

## 1. 문제 정의 — 무엇을 학습하고 무엇을 학습하지 않는가

| 필드 | 방식 | 근거 |
|---|---|---|
| category (5클래스) | **파인튜닝 분류** | 핵심 목표. 5클래스라 소규모 데이터로도 고정확도 가능 |
| color | **비학습 — 픽셀 통계** | 색 라벨은 노이즈가 크고(같은 옷도 "네이비"/"남색"/"청"), 중앙 크롭 HSV 통계 + 한국어 색명 매핑이 더 안정적. avatar_builder 팔레트와 자연 호환 |
| style_tags | v1에서 빈 배열, **v2에서 멀티라벨 헤드 추가** | K-Fashion 스타일 라벨 확보 후. v1 범위를 좁혀 완주 우선 |
| brand_guess | 항상 null | 브랜드 인식은 분류 문제가 아님. 상품 URL 경로(product_info)가 담당 |

## 2. 데이터

### 2.1 1차 (즉시 시작): Kaggle Fashion Product Images (Small)
- ~44,000장 상품컷, `masterCategory`/`subCategory`/`articleType`/`baseColour` CSV 라벨
- 다운로드 즉시 가능 (Kaggle 계정만) — **승인 대기 없이 오늘 시작할 수 있는 이유**
- 상품컷 도메인 = product_url 강화 경로와 동일 도메인이라 실전 궁합도 좋음

`articleType` → 5슬롯 매핑 (prepare_data.py에 표로 하드코딩):

| 우리 슬롯 | Kaggle articleType 예시 |
|---|---|
| 상의 | Tshirts, Shirts, Tops, Sweaters, Sweatshirts, Kurtas |
| 하의 | Jeans, Trousers, Shorts, Skirts, Track Pants, Leggings |
| 아우터 | Jackets, Blazers, Coats(=articleType 없음 → subCategory로), Rain Jacket |
| 신발 | Casual Shoes, Sports Shoes, Heels, Sandals, Flip Flops, Formal Shoes |
| 액세서리 | Caps, Hats, Watches, Belts, Handbags(→제외 검토), Sunglasses |
| (제외) | 화장품·향수·언더웨어 등 5슬롯 밖 → 학습 제외 |

### 2.2 2차 (승인 후): AI Hub K-Fashion
- 한국 패션 이미지 + 스타일(스트릿/미니멀 등)·카테고리·색상 라벨, 착용컷 포함
- 신청·승인 필요(보통 수일). 승인되면 1차 모델에 이어서 **도메인 적응 파인튜닝**
  (셀카/착용컷 도메인 + 한국 스타일 태그 → v2 style_tags 헤드 학습)

### 2.3 스플릿·불균형
- train/val/test = 8:1:1, 라벨 기준 stratified
- 클래스 불균형(액세서리 과다 등) → `WeightedRandomSampler`
- 평가 지표: **macro-F1** (정확도는 다수 클래스에 속음), 목표: 상품컷 test에서 macro-F1 ≥ 0.92

## 3. 모델·학습

- 백본: **MobileNetV3-Large (torchvision, ImageNet 사전학습)**
  - 선택 근거: 워커는 GPU 없는 환경 — CPU 추론 ~10ms급이어야 함. EfficientNet-B0도 후보지만 ONNX 변환·CPU 속도에서 MobileNetV3가 무난
- 헤드: GAP → Dropout(0.2) → Linear(→5), CrossEntropy(label smoothing 0.1)
- 입력: 224×224, ImageNet 정규화
- 증강: RandomResizedCrop(0.7~1.0), HorizontalFlip, 소폭 ColorJitter(색 헤드가 없으므로 안전)
- 스케줄 (2단계 전이학습):
  1. 백본 동결, 헤드만 lr 1e-3 × 3 epoch
  2. 마지막 블록 해동, lr 1e-4 cosine decay × 7 epoch
- 배치 64, AdamW(wd 1e-4), 재현성 seed 고정
- 학습 환경: 로컬 GPU 없으면 **Colab 무료 T4** (44k장 × 10 epoch ≈ 1~2시간).
  스크립트는 로컬/Colab 양쪽에서 동일하게 돌도록 경로만 인자화

## 4. 산출물·서빙 형식

- **ONNX로 export** → 워커는 `onnxruntime`만 추가 (PyTorch를 워커 의존성에 넣지 않는 것이 핵심 —
  requirements 무게·도커 이미지 크기 통제)
- 산출 파일 (git LFS 또는 릴리즈 첨부, 저장소 직접 커밋 금지):
  - `ai-server/models/clothes_tagger.onnx` (~10MB)
  - `ai-server/models/clothes_tagger.labels.json` — `{"version": "v1", "categories": [...]}`

## 5. 워커 통합

새 모듈 `ai-server/local_tagger.py`:

```python
def is_available() -> bool          # models/clothes_tagger.onnx 존재 여부
def analyze(image_bytes) -> dict    # Gemini와 동일한 JSON 계약
# 내부: Pillow 디코드/리사이즈 → onnxruntime 세션(전역 lazy 1회 로드) → argmax
# color: 중앙 40% 크롭 → HSV 중앙값 → 색명 매핑 (avatar_builder._COLOR_MAP 재사용 가능하게 역방향 표)
```

worker.py의 분석 지점 교체:

```python
def analyze(image_bytes) -> dict:
    if settings.GEMINI_API_KEY:
        try:
            return analyze_with_gemini(image_bytes) | {"tagger": "gemini"}
        except Exception:
            log.warning("Gemini 실패 — 로컬 모델 폴백")
    if local_tagger.is_available():
        return local_tagger.analyze(image_bytes) | {"tagger": "local"}
    return STUB_RESULT | {"tagger": "stub"}
```

- `meta_data.tagger`에 어떤 경로로 태깅됐는지 기록 — 품질 추적·디버깅용
- **캐시 키에 태거 버전 포함**: `imghash:{digest}:{tagger_version}` — 모델을 업그레이드하면
  기존 캐시를 무효화해 재분석되도록 (지금 구조면 30일간 옛 결과가 나옴)

## 6. 평가·완료 기준

1. test 스플릿 macro-F1 리포트 (`train.py`가 학습 종료 시 자동 출력)
2. **실전 검증 20장**: 실제 거울 셀카/상품컷 20장 수작업 라벨 →
   `evaluate_real.py`가 Gemini vs 로컬 모델 대조표 출력
3. 통합 테스트: GEMINI_API_KEY 없이 워커 기동 → 스캔 → category가 스텁("상의" 고정)이
   아닌 실제 분류값으로 DB에 저장되는지

## 7. 디렉토리 구조 / 일정

```
ai-server/training/
  DESIGN.md          ← 이 문서
  prepare_data.py    # Kaggle CSV → 5슬롯 매핑·스플릿 → data/{train,val,test}.csv
  train.py           # 2단계 전이학습 + macro-F1 리포트 + best.pt 저장
  export.py          # best.pt → ONNX + labels.json (+ 추론 스모크 테스트)
  evaluate_real.py   # 실전 20장 Gemini vs 로컬 대조
  requirements.txt   # torch/torchvision 등 — 학습 전용 (워커 requirements와 분리)
```

- 1주차: 데이터 준비 + 학습 + export (Colab)
- 2주차: local_tagger + 워커 통합 + 실전 평가
- 이후(v2): K-Fashion 승인 → 착용컷 도메인 적응 + style_tags 멀티라벨 헤드
