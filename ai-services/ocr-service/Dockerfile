FROM python:3.12-slim
WORKDIR /app
# PaddleOCR 需要 libgomp 等系统库；LibreOffice 用于 Office 文件 fallback 转换
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 libgl1 libglib2.0-0 libreoffice-writer libreoffice-calc libreoffice-impress \
    fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py .
EXPOSE 8001
CMD ["python", "main.py"]
