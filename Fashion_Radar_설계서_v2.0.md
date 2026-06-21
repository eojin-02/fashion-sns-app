# Project Fashion-Radar 설계서 v2.0 (수정·보강판)

> v1.0 대비 변경 핵심: ① 고스트 토큰 매핑 노출 제거(익명성 구조 재설계) ② iOS BLE 제약 반영한 하이브리드 근접 감지 ③ RSSI 거리 계산 → 이진 근접 판정으로 전환 ④ DB 정규화 및 누락 테이블 추가 ⑤ 인증·이미지 파이프라인·AI 비용 통제 신설 ⑥ 리스크 우선 마일스톤 재배치

---

## 1. PRD 보강 사항

### 1.1 유지되는 핵심 철학
"지도 폐지 + 가시거리 레이더"라는 v1.0의 문제 정의는 그대로 유지한다. 다만 아래의 비기능 요구사항을 추가한다.

### 1.2 추가 기능 요구사항

| 기능명 | 상세 요구사항 | 우선순위 | 추가 사유 |
|---|---|---|---|
| 고스트 모드 (가시성 토글) | 유저가 언제든 "나를 레이더에 노출하지 않음"을 선택 가능. 기본값은 OFF(비노출)로 두고 명시적 opt-in | High (MVP) | 위치 기반 SNS는 opt-out이 아니라 opt-in이 법적·윤리적 기본값. 스토어 심사 리젝 사유이기도 함 |
| 신고 / 차단 | 특정 유저 차단 시 양방향으로 레이더·피드에서 상호 비노출 | High (MVP) | 근접 기반 서비스에서 차단 기능 부재는 출시 불가 수준의 결함 |
| 행인(Bystander) 보호 | 거울 셀카 업로드 시 본인 외 인물 얼굴 자동 블러 (AI 서버에서 얼굴 검출 후 처리) | High (MVP) | 길거리 촬영 사진에 타인이 찍히는 건 필연. 초상권 분쟁 예방 |
| 연령 제한 | 만 14세 이상 가입 (본인인증 또는 약관 기반) | High (MVP) | 미성년자 위치 노출 리스크 차단 |
| 찜하기 / 아이템 북마크 | 타 유저 착장 아이템 찜, 마이페이지에서 모아보기 | High (MVP) | v1.0 시나리오에 등장하지만 데이터 모델에 누락되어 있었음 |
| 브랜드 콘테스트 | 런웨이 제출 / 유저 투표 / 보상 지급 | Medium (Phase 2) | v1.0 유지. 단, 데이터 모델을 본 문서에서 선반영 |

---

## 2. 시스템 아키텍처 (수정)

### 2.1 기술 스택 확정

- **Client**: Flutter (Dart)
  - Android: Foreground Service 기반 BLE Advertising + Scanning
  - iOS: **포그라운드 한정 BLE** + 서버 Presence 보조 (사유는 2.3 참조)
- **Application Server**: Java 21 + Spring Boot (최신 안정 버전 기준)
  - **Virtual Threads 활성화** (`spring.threads.virtual.enabled=true`): 위치 갱신·레이더 resolve처럼 짧고 빈번한 블로킹 I/O 요청이 대량 유입되는 워크로드에 적합. WebFlux로 갈아타지 않고도 동시성 확보 — 학부 협업에서 리액티브 러닝커브를 피하는 실용적 선택
  - WebSocket(STOMP) 엔드포인트: 동네 방 실시간 피드 push (Redis Pub/Sub → STOMP 브로커 릴레이)
- **AI Inference Server**: Python (FastAPI) + Gemini Vision API. **동기 호출 금지, 비동기 큐 처리** (2.6 참조)
- **Cache / Realtime**: Redis (Pub/Sub, 토큰 세션, Rate Limit)
- **DB**: PostgreSQL + PostGIS
- **Storage**: S3 호환 오브젝트 스토리지 + **Presigned URL 직접 업로드** (이미지가 Spring 서버를 경유하지 않게 하여 애플리케이션 서버 부하 제거)

### 2.2 익명성 구조 재설계 — "토큰 매핑은 서버 밖으로 나가지 않는다"

**v1.0의 결함**: `room/enter` 응답에 `(user_id, ble_ghost_token)` 쌍을 전체 공개 → 누구나 BLE 신호와 유저를 연결해 추적 가능. 토큰 리프레시가 무의미해짐.

**v2.0 원칙**: 토큰 ↔ 유저 매핑은 **오직 서버(Redis)에만 존재**한다. 클라이언트 간에는 절대 교환되지 않는다.

데이터 흐름:

```
[내 폰]                         [서버]                        [상대 폰]
   │ ① 방 입장 → 내 토큰 발급      │                               │
   │◄── my_token: "tk_abc" ───────│──── 상대도 자기 토큰만 수신 ──►│
   │                              │                               │
   │ ② BLE로 tk_abc 브로드캐스트   │      ② 상대도 tk_xyz 브로드캐스트│
   │◄═══════ (BLE 근접 감지) ═══════════════════════════════════►│
   │                              │                               │
   │ ③ 스캔된 토큰 목록 업로드      │                               │
   │── POST /radar/resolve ──────►│                               │
   │   ["tk_xyz", ...]            │ ④ Redis에서 토큰→세션 해석      │
   │◄── 아바타 카드 목록 ──────────│   (user_id는 절대 미포함,      │
   │    (session_avatar_id 기준)  │    세션 한정 ID로 치환)         │
```

핵심 설계 포인트:

1. **클라이언트는 "자기 토큰"만 안다.** 방의 다른 유저 토큰 목록을 미리 받지 않는다.
2. resolve 응답은 영속 `user_id`가 아닌 **세션 스코프 `session_avatar_id`**를 사용한다. 프로필 진입(찜하기 등)이 필요할 때만 서버가 일회용 서명 토큰으로 권한을 부여한다. 영속 ID를 노출하면 세션을 넘나드는 추적(re-identification)이 가능해지기 때문.
3. 토큰 리프레시 주기는 20분 → **10분 이하**로 단축. 20분이면 핫플레이스 체류 시간 내내 동일 토큰으로 미행이 가능하다. 단, Android BLE MAC 랜덤화 주기(약 15분)와 어긋나게 설정해 MAC-토큰 상관 분석을 어렵게 한다.

### 2.3 근접 감지: BLE 단독 → 하이브리드 모델

**v1.0의 결함**: iOS는 백그라운드에서 커스텀 데이터를 담은 BLE Advertising이 불가능하다(서비스 UUID가 overflow area로 밀려나며, manufacturer data는 포그라운드에서도 송출 불가). 이건 라이브러리로 우회할 수 없는 OS 정책이다. 따라서 "백그라운드 BLE 브로드캐스팅" 전제는 iOS에서 성립하지 않는다.

**v2.0 대응 (Tiered Proximity)**:

| Tier | 수단 | 정밀도 | 플랫폼 제약 |
|---|---|---|---|
| Tier 1 | BLE 근접 감지 (양측 앱 포그라운드) | 10~20m급 | Android는 백그라운드 가능, iOS는 포그라운드 권장 |
| Tier 2 | 서버 Presence (같은 행정동 방 + 최근 N분 내 활성) | 동(Dong) 단위 | 플랫폼 무관 |

UX 규칙: Tier 1 매칭 시 "홀로그램 테두리(강조)", Tier 2만 만족 시 "일반 갤러리 노출". 즉 BLE는 **강조 신호**이지 노출의 전제 조건이 아니다. 이렇게 하면 iOS 제약이 기능 전체를 무너뜨리지 않고 강조 기능의 가용성만 낮춘다 — 단일 실패 지점을 만들지 않는 degradation 설계.

### 2.4 RSSI 처리: 거리 계산 폐기, 이진 판정 + 필터링

**v1.0의 결함**: 자유공간 경로 손실 공식은 도심 멀티패스·인체 차폐 환경에서 ±50% 이상의 오차를 낸다. "20m 이내" 계산은 신뢰할 수 없다.

**v2.0 방식**:

```
입력: 토큰별 RSSI 샘플 스트림
1) 슬라이딩 윈도우(최근 5~10개 샘플) 이동 중앙값(median) — 스파이크 제거
2) 히스테리시스 이진 판정:
   - 비활성 → 활성: median RSSI ≥ -80 dBm 이 2회 연속
   - 활성 → 비활성: median RSSI < -90 dBm 이 3회 연속 (또는 60초 무신호)
```

거리를 미터로 환산하지 않는 이유: 환산 결과를 UI에 쓰는 순간 "3m 거리에 있음" 같은 **방향·거리 추적 단서**를 다시 제공하게 된다. 프라이버시 설계와도 일관되게, 시스템은 "가시거리 안/밖"이라는 1비트 정보만 다룬다. 히스테리시스로 ON/OFF 임계값을 분리하는 건 경계 RSSI에서 테두리가 깜빡이는 플리커 현상을 막기 위함이다.

### 2.5 행정구역 방 관리 (v1.0 유지 + 보완)

- PostGIS 행정동 폴리곤 `ST_Contains` 판정, 경계 통과 시에만 `room/enter` 호출 — v1.0 설계 유지 (좋은 설계).
- 보완 1: 경계에서 GPS 오차로 인한 방 핑퐁(성수1가 ↔ 성수2가 왕복) 방지를 위해 **경계 버퍼 50m + 최소 체류 시간 30초** 조건 추가.
- 보완 2: 상시 GPS 폴링은 배터리를 태운다. Android Geofencing API / iOS Region Monitoring 같은 OS 레벨 지오펜스에 위임하고, 앱은 경계 이벤트 콜백만 수신한다.

### 2.6 AI 파이프라인: 동기 → 비동기 큐

**v1.0의 누락**: Vision API 호출은 수 초가 걸리고 비용이 발생한다. 업로드 요청과 동기로 묶으면 타임아웃·재시도 폭탄·비용 통제 불가.

**v2.0 흐름**:

1. 클라이언트가 Presigned URL로 S3에 이미지 직접 업로드
2. `POST /wardrobe/scan` 은 S3 key만 받아 **작업을 큐에 적재하고 202 Accepted + job_id 즉시 반환**
3. FastAPI 워커가 큐 소비 → Gemini Vision 호출 → 결과 DB 저장 → WebSocket/푸시로 완료 알림
4. 비용 통제: 유저당 일일 스캔 횟수 제한(Redis 카운터), 동일 이미지 해시 캐싱(같은 사진 재분석 방지), 얼굴 검출·블러는 Vision 호출 전에 경량 로컬 모델(예: MediaPipe)로 선처리

---

## 3. 데이터베이스 스키마 v2.0

### 3.1 v1.0 수정 사항

**`daily_codi.item_ids BIGINT[]` 폐기 → 조인 테이블 분리.**
배열 컬럼은 FK 제약을 걸 수 없어 삭제된 아이템의 유령 ID가 남고, "이 자켓이 포함된 코디 목록" 같은 역방향 조회가 인덱스를 못 탄다. 관계는 관계 테이블로 — 객체지향에서 컬렉션 연관을 일급으로 다루는 것과 같은 원리다. JSONB(`meta_data`)는 *스키마가 불확정인 속성*이라 적합하지만, item_ids는 *명확한 N:M 관계*이므로 정규화 대상이다.

### 3.2 전체 DDL

```sql
-- 유저 (v1.0 + 인증/설정 컬럼 추가)
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(100) UNIQUE NOT NULL,
    nickname      VARCHAR(30) NOT NULL,
    avatar_url    VARCHAR(255),
    radar_visible BOOLEAN NOT NULL DEFAULT FALSE,  -- 고스트 모드: 기본 비노출(opt-in)
    birth_date    DATE NOT NULL,                   -- 연령 제한 검증
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 의류 아이템 (v1.0 유지 + 분석 상태 추가)
CREATE TABLE clothes_item (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category    VARCHAR(30),
    meta_data   JSONB,
    brand_info  VARCHAR(100),
    image_key   VARCHAR(255) NOT NULL,             -- S3 object key
    scan_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING/DONE/FAILED (비동기 파이프라인 추적)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 데일리 코디 (배열 제거)
CREATE TABLE daily_codi (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 코디-아이템 조인 테이블 (신설)
CREATE TABLE daily_codi_item (
    codi_id    BIGINT NOT NULL REFERENCES daily_codi(id) ON DELETE CASCADE,
    item_id    BIGINT NOT NULL REFERENCES clothes_item(id) ON DELETE CASCADE,
    PRIMARY KEY (codi_id, item_id)
);

-- 찜하기 (신설 — v1.0 시나리오에 있으나 모델 누락)
CREATE TABLE item_like (
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id    BIGINT NOT NULL REFERENCES clothes_item(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, item_id)
);

-- 차단 (신설)
CREATE TABLE user_block (
    blocker_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id)
);

-- 콘테스트 / 출품 / 투표 (Phase 2 선반영)
CREATE TABLE contest (
    id         BIGSERIAL PRIMARY KEY,
    brand_name VARCHAR(100) NOT NULL,
    title      VARCHAR(200) NOT NULL,
    starts_at  TIMESTAMPTZ NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE contest_entry (
    id         BIGSERIAL PRIMARY KEY,
    contest_id BIGINT NOT NULL REFERENCES contest(id),
    user_id    BIGINT NOT NULL REFERENCES users(id),
    codi_id    BIGINT NOT NULL REFERENCES daily_codi(id),
    UNIQUE (contest_id, user_id)                    -- 1인 1출품
);

CREATE TABLE contest_vote (
    entry_id   BIGINT NOT NULL REFERENCES contest_entry(id) ON DELETE CASCADE,
    voter_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (entry_id, voter_id)                -- 1인 1표
);
```

**Redis에만 두는 데이터** (RDB 미저장 — 휘발성이 본질인 데이터):
- `token:{ble_token}` → `{session_avatar_id, room}` (TTL 10분) — 고스트 토큰 매핑
- `room:{dong_code}:active` → Sorted Set(최근 활성 시각) — Presence
- `ratelimit:resolve:{user_id}` (TTL 3분), `ratelimit:scan:{user_id}:daily`

고스트 토큰을 RDB에 영속화하지 않는 것 자체가 프라이버시 장치다. 토큰-유저 이력이 디스크에 남지 않으므로 사후 유출 시에도 과거 동선 복원이 불가능하다.

---

## 4. API 명세 v2.0

### 4.0 인증 (신설)

- OAuth2 소셜 로그인(카카오/구글) → 자체 JWT 발급 (Access 30분 / Refresh 14일)
- 모든 API는 `Authorization: Bearer` 필수. v1.0에는 인증 계층이 통째로 없었다.

### 4.1 의상 스캔 (비동기로 변경)

```
1) POST /api/v1/wardrobe/upload-url        → { "presigned_url", "image_key" }
2) (클라이언트가 S3에 직접 PUT)
3) POST /api/v1/wardrobe/scan  { "image_key": "..." }
   → 202 Accepted  { "job_id": "...", "item_id": 9845, "scan_status": "PENDING" }
4) 완료 시 WebSocket/푸시 알림 → GET /api/v1/wardrobe/items/9845 로 결과 조회
```

### 4.2 방 입장 (토큰 매핑 제거)

```
POST /api/v1/location/room/enter
Request : { "latitude": 37.5445, "longitude": 127.0561 }
Response: {
  "room_name": "성수동1가",
  "my_ble_token": "tk_abc123",          // 내 토큰만. 타인 토큰 목록 제거
  "token_expires_in_sec": 600,
  "gallery": [                           // Tier 2: 방 내 활성 유저 (강조 없음)
    { "session_avatar_id": "sa_991",
      "avatar_bundle_url": "https://cdn.../sa_991.gltf",
      "today_style_summary": "블랙 스트릿 룩" }
  ]
}
```

### 4.3 레이더 해석 (신설 — 익명성 재설계의 핵심)

```
POST /api/v1/radar/resolve               // Rate Limit: 유저당 3분 1회 (Redis TTL)
Request : { "scanned_tokens": ["tk_xyz", "tk_qqq"] }
Response: {
  "nearby": [
    { "session_avatar_id": "sa_991", "highlight": true }
  ]
}
// 서버 처리: 토큰 → 세션 해석 시 radar_visible=false 유저,
// 차단 관계(user_block 양방향)는 결과에서 제외
```

### 4.4 토큰 리프레시 (신설)

```
POST /api/v1/radar/token/refresh   → { "my_ble_token": "tk_new", "token_expires_in_sec": 600 }
```

---

## 5. 보안·프라이버시 아키텍처 v2.0

1. **매핑 비공개 원칙**: 토큰↔유저 매핑은 Redis에만 존재, 클라이언트 간 미교환 (2.2)
2. **토큰 리프레시 10분** + MAC 랜덤화 주기와 비동기화
3. **resolve Rate Limit 3분/회** (v1.0 유지) — 방향 벡터 추적(금속탐지기식 미행) 방지
4. **세션 스코프 아바타 ID** — 영속 user_id 비노출로 세션 간 재식별 차단
5. **기본 비노출(opt-in)** + 고스트 모드 토글 + 차단/신고
6. **행인 얼굴 자동 블러** — 업로드 파이프라인에서 Vision 호출 전 선처리
7. **연령 제한** 만 14세 이상

---

## 6. 마일스톤 v2.0 — "가장 위험한 가설을 가장 먼저 검증"

v1.0은 BLE 검증을 3주차에 배치했다. 프로젝트 성패를 가르는 최대 기술 리스크(iOS BLE 제약, RSSI 신뢰도)는 **1주차에 Spike로 검증**해야 한다. 3주차에 "iOS에서 안 되네?"를 발견하면 되돌릴 시간이 없다.

| 주차 | 목표 | 산출물 |
|---|---|---|
| 1주차 | **BLE 기술 검증 Spike**: Android↔Android, Android↔iOS(포그라운드) 토큰 브로드캐스트/스캔, 실외에서 RSSI 로그 수집 및 -80/-90 임계값 실측 튜닝 | 검증 리포트 (실패 시 Tier 2 단독 모델로 피벗 결정) |
| 2주차 | 인프라: PostgreSQL(PostGIS) + Redis + Spring Boot 골격, JWT 인증, 방 입장/Presence | 동작하는 room/enter + resolve API |
| 3주차 | AI 파이프라인: Presigned 업로드 → 큐 → FastAPI 워커 → Gemini 태깅 → 알림. 얼굴 블러 선처리 포함 | 옷장 등록 E2E 플로우 |
| 4주차 | Flutter 통합: 레이더 UI(히스테리시스 하이라이트), 갤러리, 찜하기 | 데모 가능 MVP |
| 5주차 | 통합 테스트: 토큰 리프레시·쿨타임·차단 시나리오 검증, 부하 테스트(방 입장 동시 요청) | 테스트 리포트 |
| 6주차 | 포트폴리오 빌딩: 아키텍처 다이어그램, ADR(설계 결정 기록 — 특히 "왜 지도를 없앴는가", "왜 토큰 매핑을 서버에 가뒀는가") 포함 README | GitHub 공개 |

> 포트폴리오 팁: 면접에서 가장 강력한 무기는 기능 목록이 아니라 **"v1 설계의 프라이버시 결함을 발견하고 v2로 재설계한 과정"** 그 자체다. 이 문서의 2.2절 의사결정을 ADR로 남겨라.
