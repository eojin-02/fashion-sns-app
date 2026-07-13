# Fashion-Radar 프로젝트 전체 코드 가이드

> **프로젝트 한 줄 요약**: "같은 동네에 있는 패션피플의 오늘 착장을 BLE + 서버 Presence 두 레이어로 익명 탐지하는 소셜 패션 앱"

---

## 전체 아키텍처 한눈에 보기

```
app/          ← Flutter 모바일 앱 (iOS / Android)
backend/      ← Spring Boot 3.5 API 서버 (Java 21)
ai-server/    ← Python FastAPI + 워커 (Gemini Vision AI 분석)
db/           ← PostgreSQL(PostGIS) 스키마
docker-compose.yml ← 로컬 인프라 통합 실행
```

**데이터 흐름**:

```
[Flutter 앱]
    │  BLE 광고(토큰)  ←→  근처 디바이스
    │  REST API
    ▼
[Spring Boot 백엔드]  ←→  Redis (토큰 매핑, 큐, Pub/Sub)
    │                ←→  PostgreSQL/PostGIS (유저, 의류, 코디)
    │                ←→  S3/MinIO (이미지 저장)
    │ Redis Pub/Sub
    ▼
[FastAPI AI 워커]  ← Redis 큐 소비 → Gemini Vision 태깅
```

---

## 1. 루트 레벨 파일

### `docker-compose.yml`

로컬 개발 환경 전체를 띄우는 파일. 서비스 5개로 구성된다.

| 서비스 | 이미지 | 역할 |
|---|---|---|
| `db` | `postgis/postgis:16-3.4` | PostgreSQL + 공간쿼리 확장(PostGIS). `db/init.sql`로 초기화 |
| `redis` | `redis:7-alpine` | 토큰 매핑·큐·Pub/Sub 모두 담당 |
| `minio` | `minio/minio:latest` | S3 호환 로컬 스토리지. 이미지 저장소 |
| `minio-init` | `minio/mc` | MinIO 최초 실행 시 `wardrobe` 버킷 자동 생성 |
| `backend` | Dockerfile(backend/) | Spring 앱. `--profile app` 시만 실행 |
| `ai-worker` | Dockerfile(ai-server/) | AI 워커. `--profile app` 시만 실행 |

**실무 포인트**: `backend`와 `ai-worker`에 `profiles: ["app"]`이 붙어 있다. 개발 중엔 `docker compose up -d db redis minio`로 인프라만 띄우고, 앱은 `./gradlew bootRun`으로 따로 실행하는 것이 권장 방식이다. 이렇게 해야 코드 변경 → 재시작 사이클이 빠르다.

---

## 2. `db/` — 데이터베이스 스키마

### `db/init.sql`

PostgreSQL 최초 기동 시 자동 실행되는 DDL. **스키마의 단일 진실원천(Single Source of Truth)**으로, Spring의 `ddl-auto: none` 설정과 짝을 이룬다. 즉 Hibernate가 테이블을 건드리지 않고 이 파일이 구조를 완전히 정의한다.

**테이블 목록 & 설계 이유**

#### `users`
```sql
radar_visible BOOLEAN NOT NULL DEFAULT FALSE  -- 고스트 모드: 기본 비노출(opt-in)
birth_date    DATE NOT NULL                   -- 만 14세 이상 가입 제한
```
`radar_visible`이 `FALSE`가 기본값인 이유: 사용자가 명시적으로 "보이기"를 켜야만 레이더에 노출된다(opt-in). 앱을 설치했다고 바로 남에게 보이는 구조가 아니다.

#### `clothes_item`
```sql
scan_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'  -- PENDING / DONE / FAILED
meta_data   JSONB                                    -- 색상, 핏, 소재 등 비정형 속성
```
`meta_data`를 JSONB로 쓰는 이유: AI가 추출하는 태그(색상·소재·핏 등)는 스키마가 미리 확정되지 않는다. 컬럼을 매번 추가하는 대신 JSONB에 자유롭게 담고, 구조화된 관계(유저, 코디)는 조인 테이블로 관리하는 혼합 전략.

#### `daily_codi` + `daily_codi_item`
`daily_codi_item`은 `daily_codi`와 `clothes_item` 사이의 N:M 조인 테이블이다. v1에서 `item_ids BIGINT[]` 배열 컬럼을 썼다가 v2에서 정규화한 흔적이 주석에 보인다(`item_ids BIGINT[] 폐기`). 배열 컬럼은 역방향 조회("이 재킷이 포함된 코디 찾기")에 인덱스를 걸기 어려워서 조인 테이블로 바꾼 것이다.

#### `user_block`
복합 PK `(blocker_id, blocked_id)`. 차단 관계는 단방향이지만, 조회 시 `blocker_id = 나` 또는 `blocked_id = 나` 양쪽을 다 걸러야 상대방이 나를 차단했을 때도 내 레이더에 안 보인다(`UserBlockRepository.findAllRelatedUserIds` 참고).

#### `admin_dong`
행정동 폴리곤 테이블. `GEOMETRY(POLYGON, 4326)`에 GIST 인덱스를 걸어 `ST_Contains(geom, 좌표)`로 "이 좌표가 어느 동인지" 판정한다. 개발용으로 성수동1가·2가·한남동 3개 근사 폴리곤이 `INSERT` 되어 있고, 실서비스에선 통계청 행정동 경계 Shapefile을 적재한다.

---

## 3. `backend/` — Spring Boot API 서버

### `build.gradle`

| 의존성 | 이유 |
|---|---|
| `spring-boot-starter-web` | REST API |
| `spring-boot-starter-websocket` | STOMP WebSocket (실시간 피드) |
| `spring-boot-starter-data-jpa` | ORM (Hibernate + PostgreSQL) |
| `spring-boot-starter-data-redis` | Redis 템플릿 |
| `spring-boot-starter-security` | JWT 필터 체인 |
| `spring-boot-starter-validation` | `@Valid`, `@NotBlank` 등 |
| `jjwt:0.12.6` | JWT 생성·검증 |
| `software.amazon.awssdk:s3` | S3 Presigned URL 발급 |

**Java 21 + Virtual Threads**: `spring.threads.virtual.enabled: true` 설정으로 블로킹 I/O(DB, Redis)를 Virtual Thread로 처리한다. 플랫폼 스레드 풀 고갈 없이 동시 접속을 처리할 수 있다.

---

### `src/main/resources/application.yml`

앱 전체 설정의 중심. 환경변수가 없으면 개발용 기본값으로 동작한다.

```yaml
app:
  jwt:
    access-ttl-seconds: 1800     # 액세스 토큰 30분
    refresh-ttl-seconds: 1209600 # 리프레시 토큰 14일
  radar:
    token-ttl-seconds: 600       # BLE 고스트 토큰 10분
    resolve-cooldown-seconds: 180 # resolve API 쿨타임 3분
    presence-window-seconds: 600  # 갤러리 노출: 최근 10분 내 활성 유저
    room-dwell-seconds: 30        # 방 핑퐁 방지 최소 체류 30초
  scan:
    daily-limit: 20               # 하루 AI 스캔 최대 20회
```

**주의**: `APP_JWT_SECRET`은 운영에서 반드시 환경변수로 주입해야 한다. 기본값은 Base64 인코딩된 개발용 시크릿이라 보안상 쓰면 안 된다.

---

### `src/main/java/com/fsns/radar/`

#### `FashionRadarApplication.java`

`@SpringBootApplication` 메인 클래스. 별도 로직 없음.

---

### `auth/` — 인증

#### `JwtService.java`

JWT의 **생성과 검증** 전담 서비스.

- `createAccessToken(userId)`: 30분짜리 access 토큰 발급
- `createRefreshToken(userId)`: 14일짜리 refresh 토큰 발급
- `parseUserId(token, expectedType)`: 토큰 파싱 + 타입 검증. 유효하지 않으면 `null` 반환

**토큰 타입 분리 이유**: `claim("type", "access" | "refresh")`를 심어두고, access 토큰으로 refresh 엔드포인트를 호출하거나 그 반대를 시도할 때 `null`을 반환해 막는다.

#### `JwtAuthFilter.java`

모든 요청에서 `Authorization: Bearer {token}` 헤더를 파싱하고, 유효하면 `SecurityContextHolder`에 `userId`를 `Principal`로 설정하는 필터.

`OncePerRequestFilter`를 상속해 요청당 1번만 실행된다. 필터가 성공하면 컨트롤러에서 `(Long) auth.getPrincipal()`로 userId를 꺼낼 수 있다.

#### `AuthController.java`

**`POST /api/v1/auth/dev-login`**

소셜 로그인(카카오/구글) 연동 전 개발 단계용 엔드포인트. 이메일+닉네임+생년월일을 받아 유저를 생성하거나 찾은 뒤 토큰 쌍을 발급한다.

만 14세 미만 체크:
```java
if (Period.between(req.birth_date(), LocalDate.now()).getYears() < MIN_AGE)
    throw new ApiException(HttpStatus.FORBIDDEN, "만 14세 이상만 가입할 수 있습니다");
```

**`POST /api/v1/auth/refresh`**

refresh 토큰으로 새 액세스+리프레시 토큰 쌍을 발급한다. DB에서 유저 존재 여부를 재확인해 탈퇴한 유저의 토큰으로 재발급되는 것을 막는다.

---

### `config/` — 인프라 설정

#### `SecurityConfig.java`

Spring Security 필터 체인 설정.

- CSRF 비활성화 (JWT 기반 stateless API이므로 불필요)
- 세션 생성 안 함 (`STATELESS`)
- `/api/v1/auth/**`, `/ws/**`, `/error` 는 인증 없이 허용
- 나머지 전부 `JwtAuthFilter`를 거쳐야 접근 가능

#### `WebSocketConfig.java`

STOMP WebSocket 설정.

- 엔드포인트: `/ws`
- 구독 토픽: `/topic/room/{dong_code}` (동네 방 실시간 피드)
- 구독 토픽: `/topic/scan/{user_id}` (AI 스캔 완료 알림)

#### `S3Config.java`

AWS S3 Presigned URL 서명자 설정. `pathStyleAccessEnabled(true)`로 MinIO 호환 모드를 켠다. `public-endpoint`로 서명해야 클라이언트가 서명된 URL로 직접 PUT할 때 서명이 일치한다.

---

### `common/` — 공통 유틸

#### `ApiException.java`

컨트롤러에서 던지는 비즈니스 예외. `HttpStatus`와 메시지를 함께 담는다.

#### `GlobalExceptionHandler.java`

`@RestControllerAdvice`로 전역 예외 처리.

- `ApiException` → 해당 HTTP 상태 코드 + `{"error": "..."}` JSON 반환
- `MethodArgumentNotValidException` → 400 + 첫 번째 필드 오류 메시지 반환

---

### `user/` — 유저

#### `User.java`

JPA 엔티티. 주요 필드:
- `radarVisible`: 기본 `false`. 고스트 모드 기본값.
- `birthDate`: 연령 제한 검증용.

#### `UserBlock.java` / `UserBlockRepository.java`

복합 PK `(blocker_id, blocked_id)`를 `@EmbeddedId`로 표현한다. `findAllRelatedUserIds(userId)` 쿼리가 핵심—자신이 차단한 사람과 자신을 차단한 사람 모두를 Set으로 반환해, 레이더·갤러리에서 양방향으로 필터링한다.

#### `UserReport.java` / `UserReportRepository.java`

신고 기록 저장. 현재는 DB에 쌓기만 하며 관리자 페이지 등 후처리는 Phase 2.

#### `UserController.java`

| 엔드포인트 | 설명 |
|---|---|
| `GET /api/v1/users/me` | 내 프로필 조회 |
| `PATCH /api/v1/users/me/visibility` | 고스트 모드 ON/OFF |
| `POST /api/v1/users/{id}/block` | 차단 |
| `DELETE /api/v1/users/{id}/block` | 차단 해제 |
| `POST /api/v1/users/{id}/report` | 신고 |

---

### `wardrobe/` — 옷장

#### `ClothesItem.java`

JPA 엔티티. `scanStatus`는 `PENDING → DONE | FAILED` 상태 머신. `metaData`는 Hibernate의 `@JdbcTypeCode(SqlTypes.JSON)`으로 `Map<String, Object>`를 JSONB에 매핑한다.

`summary()` 메서드: 갤러리 카드에 표시할 짧은 요약. "나이키 상의" 형태로 반환.

#### `WardrobeService.java`

**이미지 업로드 플로우 (서버 미경유)**

```
앱 → POST /wardrobe/upload-url (Presigned URL 요청)
       ↓
     S3Presigner.presignPutObject() → URL 반환
       ↓
앱 → PUT {presignedUrl} 직접 S3에 업로드 (서버 안 거침)
       ↓
앱 → POST /wardrobe/scan (imageKey 전달)
       ↓
     ClothesItem 생성(PENDING) + Redis 큐에 job 적재 → 202 반환
```

`enqueueScan()`에서 `imageKey.startsWith("u" + userId + "/")`를 검증해 타인의 이미지 key로 스캔을 요청하는 것을 막는다.

일일 스캔 제한: Redis 카운터 `ratelimit:scan:{userId}:daily`를 24시간 TTL로 관리.

#### `ItemLike.java` / `ItemLikeRepository.java`

찜하기 N:M 테이블. 복합 PK `(user_id, item_id)`.

#### `WardrobeController.java`

| 엔드포인트 | 설명 |
|---|---|
| `POST /wardrobe/upload-url` | Presigned PUT URL 발급 |
| `POST /wardrobe/scan` | AI 스캔 큐 적재 → 202 |
| `GET /wardrobe/items` | 내 아이템 목록 |
| `GET /wardrobe/items/{id}` | 단건 조회 |
| `POST /wardrobe/items/{id}/like` | 찜 |
| `DELETE /wardrobe/items/{id}/like` | 찜 해제 |
| `GET /wardrobe/likes` | 내가 찜한 아이템 모아보기 |

---

### `codi/` — 오늘의 코디

#### `DailyCodi.java`

유저당 1개(`unique`)인 오늘의 코디 헤더. `touch()`는 `updatedAt`을 현재 시각으로 갱신한다.

#### `DailyCodiItem.java`

코디-아이템 조인 테이블. 복합 PK `(codi_id, item_id)`를 `@EmbeddedId`로 구현.

#### `CodiController.java`

**`PUT /api/v1/codi`** — 오늘의 코디 저장/교체

1. 요청된 `item_ids`가 전부 본인 소유인지 검증
2. 기존 코디가 있으면 `touch()` 후 재사용, 없으면 생성
3. 기존 코디 아이템 전체 삭제 후 새 아이템으로 교체
4. Redis에서 현재 방 코드를 찾아 `CODI_UPDATED` 이벤트를 Pub/Sub으로 발행

같은 방의 유저들에게 실시간으로 코디 업데이트 알림이 가는 구조.

**`GET /api/v1/codi`** — 내 오늘의 코디 조회

---

### `location/` — 위치/방

#### `RoomService.java`

**방 입장의 전체 흐름**

```
1. locate(lat, lng)
   → PostGIS ST_Contains로 동 판정
   → 서비스 외 지역이면 404

2. applyDwellGuard(userId, dong)
   → 마지막 방 입장 후 30초 미만이면 직전 방 유지
   → 경계에서 A↔B 방을 왔다갔다하는 "핑퐁" 방지

3. Redis Sorted Set room:{dong_code}:active 에 userId, 현재 timestamp 추가
   → 10분 지난 오래된 멤버 제거

4. RadarService.issueToken() → BLE 고스트 토큰 발급

5. buildGallery() → 같은 방 + 최근 10분 내 활성 유저 카드 목록 구성

6. FeedPublisher.publish(dongCode, ENTER 이벤트) → 같은 방 실시간 알림
```

`buildGallery()`에서 `radar_visible=false`인 유저는 필터링된다. 또한 차단 관계인 유저도 제외.

#### `RoomController.java`

**`POST /api/v1/location/room/enter`**

위도·경도를 받아 방 입장 처리. 응답에는 **내 BLE 토큰만** 포함되고, 타인 BLE 토큰 목록은 절대 포함되지 않는다. 이것이 익명성 설계의 핵심이다.

---

### `radar/` — 레이더 (BLE 토큰 해석)

#### `RadarService.java`

**익명성의 핵심 설계**

```
user_id (영속 ID)  →  Redis에만 존재 (DB 미영속)
session_avatar_id  →  유저가 외부에서 보이는 식별자 (방 세션 단위 유효)
BLE 토큰           →  디바이스가 광고하는 랜덤 문자열
```

외부 세계(앱, 다른 유저)는 `session_avatar_id`만 알 수 있다. `user_id`는 서버 내부에서만 쓰인다. Redis 매핑이 만료되면 과거 세션과 현재 유저를 연결할 수 없다.

**주요 Redis 키 구조**

| 키 | 내용 | TTL |
|---|---|---|
| `token:{bleToken}` | `TokenSession` JSON (`userId`, `sessionAvatarId`, `dongCode`) | 10분 |
| `mytoken:{userId}` | 내 현재 BLE 토큰 | 10분 |
| `sa:{userId}:{dongCode}` | session_avatar_id | 2시간 |
| `sa-rev:{sessionAvatarId}` | userId (서버 내부 역방향 조회용) | 2시간 |
| `ratelimit:resolve:{userId}` | resolve 쿨타임 락 | 3분 |

**`resolve()` 메서드**

1. Rate Limit 체크 (3분 1회). 쿨타임 미적용 시 방향 벡터 추적이 가능해진다.
2. 스캔된 토큰 목록에서 Redis로 `TokenSession` 조회
3. 나 자신, 차단 관계, `radar_visible=false` 유저 필터링
4. `NearbyAvatar(session_avatar_id, highlight=true)` 목록 반환

#### `RadarController.java`

| 엔드포인트 | 설명 |
|---|---|
| `POST /api/v1/radar/resolve` | BLE 스캔 토큰 → 아바타 카드 변환 |
| `POST /api/v1/radar/token/refresh` | BLE 고스트 토큰 10분 갱신 |

---

### `feed/` — 실시간 피드

#### `FeedPublisher.java`

이벤트를 Redis Pub/Sub 채널 `feed:{dong_code}`에 발행한다. 서버 인스턴스가 여러 대여도 Redis를 경유하므로 모든 인스턴스의 구독자에게 전달된다.

발행되는 이벤트 타입:
- `ENTER`: 유저 방 입장 (session_avatar_id 포함)
- `CODI_UPDATED`: 유저 코디 업데이트 (session_avatar_id 포함)

#### `FeedRelay.java`

Redis Pub/Sub → STOMP WebSocket 브리지.

- `feed:*` 패턴 구독 → `/topic/room/{dong_code}` WebSocket 토픽으로 전달
- `scan-result:*` 패턴 구독 → `/topic/scan/{userId}` WebSocket 토픽으로 전달

AI 워커가 스캔을 완료하면 `scan-result:{userId}` 채널에 발행하고, 이 릴레이가 해당 유저의 앱으로 즉시 푸시한다.

---

### `contest/` — 콘테스트 (Phase 2)

#### `Contest.java`, `ContestEntry.java`, `ContestVote.java`

브랜드가 주최하는 코디 콘테스트 엔티티. `UNIQUE (contest_id, user_id)` 제약으로 1인 1출품, 복합 PK `(entry_id, voter_id)`로 1인 1표를 강제한다.

현재 DB 스키마에만 반영되어 있고, Controller/Service는 Phase 2 구현 예정.

---

### `src/test/` — 테스트

#### `JwtServiceTest.java`

`JwtService`의 단위 테스트. 인프라 없이 실행 가능.

#### `FashionRadarApplicationTests.java`

Spring Context 로드 테스트. DB/Redis 없이도 통과하도록 `@Tag("integration")` 없이 작성.

**테스트 구분**:
- `./gradlew test`: 단위 테스트만 (인프라 불필요)
- `./gradlew integrationTest`: `@Tag("integration")` 포함 (DB, Redis 필요)

---

## 4. `ai-server/` — AI 분석 서버

Python 기반. FastAPI로 상태 확인 API를 제공하고, 실제 분석은 `worker.py`가 담당한다.

### `settings.py`

환경변수를 읽어 설정값을 모듈 단위로 노출. `GEMINI_API_KEY`가 없으면 워커가 스텁 모드로 동작한다.

### `main.py` — FastAPI 앱

**`GET /health`**: 헬스체크  
**`GET /queue/depth`**: Redis 큐(`queue:scan`) 적체 깊이 모니터링

실제 AI 분석은 이 프로세스가 아니라 `worker.py`가 한다. 큐 소비를 별도 프로세스로 분리한 이유는 blocking I/O(S3 다운로드, Gemini 호출)가 FastAPI의 비동기 이벤트 루프를 막지 않도록 하기 위해서다.

### `worker.py` — AI 분석 워커

Redis 큐를 **무한 루프 + `BRPOP`**으로 소비하는 blocking 워커.

**처리 순서**

```
1. Redis BRPOP queue:scan (최대 5초 대기)
2. S3에서 이미지 다운로드
3. blur_bystander_faces() — 타인 얼굴 블러
4. SHA-256 해시 캐시 조회 → 같은 사진 재분석 방지
5. analyze_with_gemini() — Gemini 2.0 Flash 태깅
6. PostgreSQL clothes_item 업데이트 (DONE)
7. Redis Publish scan-result:{user_id} — 완료 알림
```

**`blur_bystander_faces()`**

OpenCV Haar Cascade로 얼굴 검출 후, 가장 큰 얼굴을 촬영자 본인으로 간주하고 나머지에 가우시안 블러를 적용한다. Vision API 호출 전 로컬 선처리라 추가 API 비용이 없다.

**`analyze_with_gemini()`**

Gemini Vision에게 다음 JSON 형식으로 답하도록 요청한다:
```json
{"category": "상의", "brand_guess": "나이키", "color": "흰색", "style_tags": ["스트릿"]}
```
`GEMINI_API_KEY`가 없으면 스텁 데이터(`{"category": "상의", "style_tags": ["stub"]}`)를 반환해 개발 중 API 비용 없이 파이프라인 전체를 테스트할 수 있다.

**이미지 해시 캐싱 이유**: 같은 사진을 여러 번 올려도 Gemini를 한 번만 호출하고 30일간 캐시한다. 비용 통제.

### `requirements.txt`

| 패키지 | 용도 |
|---|---|
| `fastapi` | 상태 API 서버 |
| `uvicorn` | ASGI 서버 |
| `redis` | Redis 큐/Pub/Sub |
| `psycopg[binary]` | PostgreSQL (psycopg3) |
| `boto3` | S3 이미지 다운로드 |
| `google-genai` | Gemini Vision API |
| `opencv-python-headless` | 얼굴 검출·블러 |
| `pillow` | 이미지 처리 유틸 |

### `Dockerfile`

`python worker.py`를 CMD로 실행한다.

---

## 5. `app/` — Flutter 모바일 앱

### `pubspec.yaml`

Flutter 앱 설정 파일. 의존성 패키지 목록이 정의되어 있다.

### `lib/main.dart` — 앱 진입점

`FashionRadarApp`: MaterialApp 설정. 다크 테마 + Cyan 포인트 컬러.

`_Bootstrap`: 앱 시작 시 자동 `dev-login` 후 `RadarScreen`으로 이동하는 개발용 부트스트랩 위젯. 카카오/구글 OAuth 연동 시 이 위젯을 소셜 로그인 화면으로 교체한다.

### `lib/core/api_client.dart` — API 클라이언트

서버와의 모든 HTTP 통신 담당. 토큰 관리 포함.

- 기본 URL: `http://10.0.2.2:8080` (Android 에뮬레이터 → 호스트 PC)
- `devLogin()`: 액세스/리프레시 토큰 저장
- `refreshAuth()`: 리프레시 토큰으로 갱신
- `enterRoom()`: 방 입장 (내 BLE 토큰 수신)
- `resolveRadar()`: BLE 스캔 토큰 → 아바타 변환
- `refreshBleToken()`: BLE 토큰 10분 갱신
- `createUploadUrl()` + `uploadImage()`: Presigned 이미지 업로드
- `setGhostMode()`: 고스트 모드 ON/OFF
- `blockUser()`: 차단

`ApiException`: 서버 오류 응답을 Dart 예외로 래핑.

### `lib/ble/ble_service.dart` — BLE 근접 감지

**`BleProximityService`**

BLE 광고(Advertising)와 스캔(Scanning)을 관리하고, RSSI 기반 "가시거리 안/밖" 판정을 내린다.

**플랫폼 차이**:
- Android: Foreground Service로 백그라운드 BLE 광고 가능
- iOS: 포그라운드에서만 서비스 UUID 광고 가능 (OS 정책 제약)

**현재 상태**: `startAdvertising()`과 `startScanning()`은 TODO 주석. 1주차 Spike에서 `flutter_blue_plus` 또는 플랫폼 채널로 구현 예정이며, 실패 시 Tier 2(서버 Presence) 단독 모델로 피벗할 계획.

`onTokensChanged` 콜백: 가시거리 판정이 바뀔 때마다 활성 토큰 집합을 알린다. `RadarScreen`이 이 콜백을 받아 `POST /radar/resolve`를 호출한다.

### `lib/radar/rssi_filter.dart` — RSSI 필터

**`RssiHysteresisFilter`**

BLE RSSI 신호 강도를 "가시거리 안/밖" 이진값으로 변환하는 필터.

**핵심 설계 원칙**: 거리를 미터로 계산하지 않는다. "3m 거리에 있음"을 알려주는 순간 방향·거리 추적 단서를 제공하게 되므로, 오직 "가시거리 안/밖" 1비트만 다룬다.

**히스테리시스 임계값 분리**:

| | 임계값 | 연속 횟수 |
|---|---|---|
| 비활성 → 활성 (enter) | ≥ -80dBm | 2회 연속 |
| 활성 → 비활성 (exit) | < -90dBm | 3회 연속 |

진입 임계값(-80)과 이탈 임계값(-90)을 다르게 설정하는 이유: 경계 RSSI에서 테두리가 깜빡이는 플리커(flicker) 현상을 막기 위해서다.

이동 중앙값(median): 슬라이딩 윈도우 최대 7개 샘플의 중앙값을 사용해 멀티패스·인체 차폐로 인한 스파이크(순간 튐)를 제거한다.

60초 무신호 시 자동 비활성화(`checkTimeout()`).

### `lib/radar/radar_screen.dart` — 레이더 화면

**UI 구조**: 2열 그리드. 각 카드는 같은 방의 다른 유저 아바타.

**Tier 1/2 통합 UX**:
- Tier 2 (서버 Presence): 방에 입장하면 갤러리 전체 로드 → 모든 카드 표시
- Tier 1 (BLE): 가시거리 판정 변화 → `resolve` 호출 → `highlighted` 집합 업데이트 → 해당 카드에 시안색 테두리 + 그림자 + "가시거리 안" 텍스트

**`_AvatarCard`**: `highlighted` 여부에 따라 `AnimatedContainer`로 테두리 애니메이션.

**클라이언트 사이드 쿨타임**: 3분 미만이면 서버 호출 자체를 건너뛴다. 서버도 거부하지만 네트워크 요청 낭비를 막기 위해 앱에서 먼저 체크한다.

---

## 6. 주요 설계 결정 요약

### 익명성 레이어 (가장 중요한 설계)

| 레이어 | 노출 식별자 | 영속성 |
|---|---|---|
| 앱 ↔ 앱 (BLE) | BLE 토큰 (랜덤 hex) | 10분 TTL |
| 앱 ↔ 서버 | session_avatar_id | 세션(2시간) |
| 서버 내부 | user_id | DB 영속 |

`user_id`는 절대 API 응답에 포함되지 않는다.

### 비동기 AI 파이프라인

```
클라이언트  →  Spring (202 즉시 반환)  →  Redis 큐
                                              ↓
                                        Python 워커
                                              ↓
                                      DB 업데이트
                                              ↓
                                       Redis Pub/Sub
                                              ↓
                                      Spring STOMP 릴레이
                                              ↓
                                        클라이언트 WebSocket 수신
```

AI 분석 시간(수 초)을 HTTP 응답에 묶지 않는 구조. 클라이언트는 202를 받고 기다리다가 WebSocket으로 완료를 통보받는다.

### 방 핑퐁 방지

경계 근처에서 A동 ↔ B동을 왔다갔다하는 현상을 `room-dwell-seconds: 30` 최소 체류 시간으로 막는다. 방이 바뀌려면 직전 방 진입 후 30초가 지나야 한다.
