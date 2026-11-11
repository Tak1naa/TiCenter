FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py .
USER 65534:65534
ENV BIND_HOST=0.0.0.0
EXPOSE 8004
CMD ["python", "main.py"]
