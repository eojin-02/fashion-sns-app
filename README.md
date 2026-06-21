# Project Fashion-Radar v2.0

> "지도 폐지 + 가시거리 레이더" — 같은 동네에 있는 사람들의 착장을 익명으로 발견하는 위치 기반 패션 SNS.
> [설계서 v2.0](Fashion_Radar_설계서_v2.0.md)을 구현한 모노레포입니다.

## 구조

```
Fsns/
├── backend/      Java 21 + Spring Boot 3.5 (Virtual Threads, STOMP, Redis, PostGIS)
├── ai-server/    Python FastAPI + 스캔 워커 (Gemini Vision, 얼굴 블러 선처리)
├── app/          Flutter 클라이언트 골격 (RSSI 히스테리시스 필터, Tiered Proximity)
├── db/init.sql   PostgreSQL DDL (설계서 3.2) + 개발용 행정동 폴리곤
└── docker-compose.yml   PostGIS + Redis + MinIO(S3 호환)
```

## 빠른 시작

```powershell
# 1. 인프라 기동 (PostGIS / Redis / MinIO + wardrobe 버킷 자동 생성)
docker compose up -d db redis minio minio-init

# 2. 백엔드 (포트 8080)
cd backend; .\gradlew.bat bootRun

# 3. AI 워커 (별도 터미널)
cd ai-server
pip install -r requirements.txt
python worker.py          # GEMINI_API_KEY 없으면 스텁 태깅으로 동작

# 4. (선택) AI 상태 API
uvicorn main:app --port 8000
```

### 테스트

```powershell
cd backend
.\gradlew.bat test              # 단위 테스트 — 인프라 불필요, Docker 없이 통과 (JwtServiceTest 6건)
.\gradlew.bat integrationTest   # 컨텍스트 로드 — DB/Redis 기동 후 실행
```

### E2E 플로우 검증 (curl, Docker 필요)

```powershell
# 로그인 (만 14세 미만이면 403)
$auth = (curl.exe -s -X POST localhost:8080/api/v1/auth/dev-login -H "Content-Type: application/json" `
  -d '{\"email\":\"a@b.c\",\"nickname\":\"tester\",\"birth_date\":\"2000-01-01\"}') | ConvertFrom-Json
$h = "Authorization: Bearer $($auth.access_token)"

# 방 입장 — 내 BLE 토큰만 수신, 타인 토큰 목록 없음 (설계서 2.2)
curl.exe -s -X POST localhost:8080/api/v1/location/room/enter -H $h -H "Content-Type: application/json" `
  -d '{\"latitude\":37.5445,\"longitude\":127.0561}'

# 레이더 해석 — 3분 쿨타임, 응답은 session_avatar_id만
curl.exe -s -X POST localhost:8080/api/v1/radar/resolve -H $h -H "Content-Type: application/json" `
  -d '{\"scanned_tokens\":[\"tk_xxx\"]}'
```

## 설계서 → 구현 매핑

| 설계서 | 구현 위치 |
|---|---|
| 2.2 익명성 구조 (토큰 매핑은 서버 밖으로 안 나감) | `backend/.../radar/RadarService.java` — 매핑은 Redis에만, 응답은 세션 스코프 `session_avatar_id` |
| 2.3 Tiered Proximity (BLE는 강조 신호) | `app/lib/ble/ble_service.dart` + `RoomService.buildGallery` (Tier 2 갤러리) |
| 2.4 RSSI 이진 판정 + 히스테리시스 | `app/lib/radar/rssi_filter.dart` (이동 중앙값, -80/-90 dBm, 60초 타임아웃) |
| 2.5 행정동 방 + 핑퐁 방지 | `backend/.../location/RoomService.java` (ST_Contains, 30초 체류 가드) |
| 2.6 비동기 AI 파이프라인 | `WardrobeService`(202 + 큐 적재) → `ai-server/worker.py`(블러→해시 캐시→Gemini) |
| 3.2 DDL | `db/init.sql` (+ `user_report`, `admin_dong`은 구현상 보강) |
| 4.0 인증 | `auth/` — JWT Access 30분 / Refresh 14일. 소셜 OAuth는 `AuthController`에 연동 지점 주석 |
| 5 보안 장치 | resolve 쿨타임 3분, 토큰 TTL 10분, 고스트 모드 opt-in, 양방향 차단 필터 |

## 설계서 대비 구현 노트

- **dev-login**: 카카오/구글 OAuth 연동 전 동일한 JWT 플로우를 검증하기 위한 개발용 엔드포인트. 운영 배포 전 제거 대상.
- **user_report 테이블**: 설계서 1.2에 신고가 MVP로 명시되어 있으나 3.2 DDL에 누락 → 보강.
- **daily_codi UNIQUE(user_id)**: "오늘의 코디 1개 갱신" 모델을 스키마로 강제.
- **얼굴 블러 휴리스틱**: 최대 크기 얼굴을 본인으로 간주하고 나머지를 블러. 정밀한 본인 식별은 Phase 2.
- **행정동 폴리곤**: `db/init.sql`의 성수1가/성수2가/한남동은 개발용 근사 사각형. 실서비스는 통계청 경계 데이터 적재.
- **BLE 광고/스캔 실구현**: 1주차 Spike 항목(설계서 6). `ble_service.dart`에 플랫폼별 TODO로 표시.

## 마일스톤 현황 (설계서 6 기준)

- [x] 2주차: 인프라 + JWT 인증(단위 테스트 6건 통과) + room/enter + resolve API
- [x] 3주차: AI 파이프라인 (Presigned 업로드 → 큐 → 워커 → 태깅 → WebSocket 알림)
- [~] 4주차: Flutter 통합 — 레이더 UI/필터 골격 완료, BLE 네이티브 연동은 1주차 Spike와 함께 진행 필요
- [ ] 1주차 Spike: 실기기 BLE 검증 (Android↔Android, Android↔iOS 포그라운드, RSSI 임계값 실측)
- [ ] 5주차: 통합 테스트 / 부하 테스트
