import os
from pathlib import Path


def _load_dotenv() -> None:
    """프로젝트 루트 .env 로드 — docker compose가 읽는 파일과 동일한 것을 공유한다.

    이미 셸에 설정된 환경변수가 항상 우선한다 ($env:GEMINI_API_KEY 등).
    외부 의존성 없이 KEY=VALUE 형식만 지원 (주석 #, 빈 줄 무시).
    """
    env_file = Path(__file__).resolve().parent.parent / ".env"
    if not env_file.is_file():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip().strip('"').strip("'")
        if key and value and key not in os.environ:
            os.environ[key] = value


_load_dotenv()

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://fsns:fsns@localhost:5432/fsns")
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")

S3_ENDPOINT = os.getenv("S3_ENDPOINT", "http://localhost:9000")
S3_ACCESS_KEY = os.getenv("S3_ACCESS_KEY", "minioadmin")
S3_SECRET_KEY = os.getenv("S3_SECRET_KEY", "minioadmin")
S3_BUCKET = os.getenv("S3_BUCKET", "wardrobe")

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
# 고정 버전 대신 최신 추적 별칭 — 구모델 무료 티어 폐기(429/404)로 스캔이 전부
# 실패했던 사고(2026-07) 재발 방지. 특정 버전이 필요하면 .env의 GEMINI_MODEL로 덮어쓴다.
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-flash-latest")

SCAN_QUEUE = "queue:scan"
RESULT_CHANNEL_PREFIX = "scan-result:"  # scan-result:{user_id} 채널로 완료 알림 발행
