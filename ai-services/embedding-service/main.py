"""
Embedding Service — SiliconFlow BGE-M3 dense + FastEmbed BM25 sparse vectors.
"""
import hashlib
import math
import os
import random as _random
import sys
from typing import Optional

from fastapi import FastAPI, HTTPException, Request, Depends
from fastapi.middleware.cors import CORSMiddleware
from openai import OpenAI
from pydantic import BaseModel
import secrets as _secrets

API_SERVICE_TOKEN = os.environ.get("API_SERVICE_TOKEN", "")

def require_api_token(request: Request):
    if not API_SERVICE_TOKEN:
        raise HTTPException(status_code=503, detail="服务未配置 API_SERVICE_TOKEN")
    auth = request.headers.get("X-Api-Token", "")
    if not _secrets.compare_digest(auth, API_SERVICE_TOKEN):
        raise HTTPException(status_code=401, detail="无效的服务 token")
    return True

EMBEDDING_API_KEY = os.environ.get("SILICONFLOW_API_KEY", "") or os.environ.get("EMBEDDING_API_KEY", "")
EMBEDDING_BASE_URL = os.environ.get("EMBEDDING_BASE_URL", "https://api.siliconflow.cn/v1")
EMBED_MODEL = os.environ.get("EMBED_MODEL", "BAAI/bge-m3")

app = FastAPI(title="Embedding Service", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

# BAAI/bge-m3 produces 1024-dimensional dense embeddings.
DIMENSIONS = int(os.environ.get("EMBEDDING_DIMENSIONS", "1024"))
USE_API = bool(EMBEDDING_API_KEY)
ALLOW_HASH_FALLBACK = os.environ.get("ALLOW_HASH_FALLBACK", "false").lower() == "true"

_client: Optional[OpenAI] = None


def get_client():
    global _client
    if _client is None and USE_API:
        _client = OpenAI(api_key=EMBEDDING_API_KEY, base_url=EMBEDDING_BASE_URL)
    return _client


class EmbedRequest(BaseModel):
    texts: list[str]


class EmbedResponse(BaseModel):
    embeddings: list[list[float]]
    dimensions: int


@app.on_event("startup")
def validate_embedding_shape_on_startup():
    """Fail startup on a wrong production vector shape; never delete or recreate Qdrant here."""
    if not USE_API or os.environ.get("VALIDATE_EMBEDDING_ON_STARTUP", "true").lower() != "true":
        return
    try:
        response = get_client().embeddings.create(model=EMBED_MODEL, input=["BGE-M3 维度探测"])
        actual = len(response.data[0].embedding) if response.data else 0
        if actual != DIMENSIONS:
            raise RuntimeError(f"embedding dimension mismatch: got {actual}, expected {DIMENSIONS}")
        print(f"[embedding] startup shape validation passed: model={EMBED_MODEL} dimensions={actual}", flush=True)
    except Exception as exc:
        print(f"[embedding] startup shape validation failed: {type(exc).__name__} {str(exc)[:160]}", flush=True)
        raise


def hash_embed(text: str, dims: int = DIMENSIONS) -> list[float]:
    """Deterministic pseudo-embedding via single-seed PRNG.
    This is a DEVELOPMENT FALLBACK. Use real embeddings in production.
    旧实现每个维度一次 SHA-256（1536 次哈希/文本），改为一次哈希作种子，
    Mersenne Twister 对同一种子跨进程稳定，结果确定且快两个数量级。
    """
    seed = int.from_bytes(hashlib.sha256(text.encode("utf-8")).digest()[:8], "big")
    rng = _random.Random(seed)
    values = [rng.random() * 2.0 - 1.0 for _ in range(dims)]
    norm = math.sqrt(sum(v * v for v in values))
    return [v / norm for v in values] if norm > 0 else values


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest, _: bool = Depends(require_api_token)):
    if not req.texts or len(req.texts) > 256:
        raise HTTPException(status_code=413, detail="批量嵌入上限 256 条/请求")
    if any(len(t) > 8000 for t in req.texts):
        raise HTTPException(status_code=413, detail="单条文本超过 8000 字符上限")
    if USE_API:
        client = get_client()
        try:
            resp = client.embeddings.create(
                model=EMBED_MODEL,
                input=req.texts,
            )
            embeddings = [d.embedding for d in resp.data]
            if embeddings and len(embeddings[0]) != DIMENSIONS:
                raise RuntimeError(f"embedding dimension mismatch: got {len(embeddings[0])}, expected {DIMENSIONS}")
            return EmbedResponse(embeddings=embeddings, dimensions=len(embeddings[0]) if embeddings else 0)
        except Exception as e:
            # 打印 API 失败原因（之前静默 fallback 掩盖了 404）
            print(f"[embedding] API failed: {type(e).__name__} {str(e)[:200]}", flush=True)
            if not ALLOW_HASH_FALLBACK:
                raise HTTPException(status_code=503, detail="dense embedding provider unavailable")

    if not ALLOW_HASH_FALLBACK:
        raise HTTPException(status_code=503, detail="dense embedding is not configured")

    # Development-only hash fallback. It must never participate in production retrieval.
    embeddings = [hash_embed(t) for t in req.texts]
    return EmbedResponse(embeddings=embeddings, dimensions=DIMENSIONS)


class EmbedQueryRequest(BaseModel):
    text: str


@app.post("/embed_query", response_model=EmbedResponse)
def embed_query_endpoint(req: EmbedQueryRequest, _: bool = Depends(require_api_token)):
    return embed(EmbedRequest(texts=[req.text]))


class SparseVector(BaseModel):
    indices: list[int]
    values: list[float]


class SparseEmbedResponse(BaseModel):
    embeddings: list[SparseVector]
    model: str


_sparse_model = None


def get_sparse_model():
    global _sparse_model
    if _sparse_model is None:
        from fastembed import SparseTextEmbedding
        _sparse_model = SparseTextEmbedding(model_name="Qdrant/bm25")
    return _sparse_model


@app.post("/embed_sparse", response_model=SparseEmbedResponse)
def embed_sparse(req: EmbedRequest, _: bool = Depends(require_api_token)):
    if not req.texts or len(req.texts) > 256:
        raise HTTPException(status_code=413, detail="批量嵌入上限 256 条/请求")
    if any(len(t) > 8000 for t in req.texts):
        raise HTTPException(status_code=413, detail="单条文本超过 8000 字符上限")
    try:
        vectors = list(get_sparse_model().embed(req.texts))
        return SparseEmbedResponse(
            embeddings=[SparseVector(
                indices=[int(v) for v in vector.indices.tolist()],
                values=[float(v) for v in vector.values.tolist()]) for vector in vectors],
            model="Qdrant/bm25")
    except Exception as e:
        print(f"[embedding] sparse embedding failed: {type(e).__name__} {str(e)[:200]}", flush=True)
        raise HTTPException(status_code=503, detail="sparse embedding unavailable")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "mode": "api" if USE_API else ("hash-fallback" if ALLOW_HASH_FALLBACK else "lexical-only"),
        "dimensions": DIMENSIONS,
        "sparse_model": "Qdrant/bm25",
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8002)
