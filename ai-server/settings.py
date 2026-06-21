import os

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://fsns:fsns@localhost:5432/fsns")
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")

S3_ENDPOINT = os.getenv("S3_ENDPOINT", "http://localhost:9000")
S3_ACCESS_KEY = os.getenv("S3_ACCESS_KEY", "minioadmin")
S3_SECRET_KEY = os.getenv("S3_SECRET_KEY", "minioadmin")
S3_BUCKET = os.getenv("S3_BUCKET", "wardrobe")

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.0-flash")

SCAN_QUEUE = "queue:scan"
RESULT_CHANNEL_PREFIX = "scan-result:"  # scan-result:{user_id} 채널로 완료 알림 발행
