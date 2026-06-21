"""AI Inference Server 상태 API.

실제 분석은 worker.py가 큐를 소비하며 수행한다 (동기 호출 금지 — 설계서 2.6).
이 앱은 헬스체크와 큐 적체 모니터링만 제공한다.

실행: uvicorn main:app --port 8000
"""
import redis
from fastapi import FastAPI

import settings

app = FastAPI(title="Fashion-Radar AI Server", version="2.0.0")
r = redis.Redis.from_url(settings.REDIS_URL, decode_responses=True)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/queue/depth")
def queue_depth():
    return {"queue": settings.SCAN_QUEUE, "depth": r.llen(settings.SCAN_QUEUE)}
