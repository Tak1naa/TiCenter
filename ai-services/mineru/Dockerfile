# MinerU CPU 解析服务（可选组件，docker compose --profile mineru 启用）
# 官方镜像基于 vllm/GPU（10GB+），无 GPU 的 PVE 用此 CPU 版更合适。
# 模型默认经 ModelScope 在首次解析时下载（数 GB，需外网），挂载 mineru-models 卷缓存。
FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 libglib2.0-0 fonts-noto-core fonts-noto-cjk fontconfig curl \
    && fc-cache -fv && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir 'mineru[core]>=3.4.0'

ENV MINERU_MODEL_SOURCE=modelscope
EXPOSE 8000

# pipeline 后端纯 CPU 无幻觉；并发上限由 MinerU 内部控制（默认 3）
CMD ["mineru-api", "--host", "0.0.0.0", "--port", "8000"]
