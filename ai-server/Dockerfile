FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
# 워커가 메인 프로세스. 상태 API가 필요하면 uvicorn을 별도 컨테이너로 띄울 것.
CMD ["python", "worker.py"]
