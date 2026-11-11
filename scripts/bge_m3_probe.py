#!/usr/bin/env python3
"""Real SiliconFlow BGE-M3 -> temporary Qdrant production-shape probe."""
from __future__ import annotations
import json, os, sys, urllib.request, uuid


def request(url: str, method: str = "GET", body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response) if response.length != 0 else {}


def main() -> int:
    key = os.environ.get("SILICONFLOW_API_KEY") or os.environ.get("EMBEDDING_API_KEY")
    if not key:
        raise SystemExit("请先在进程环境中设置 SILICONFLOW_API_KEY 或 EMBEDDING_API_KEY")
    base = os.environ.get("EMBEDDING_BASE_URL", "https://api.siliconflow.cn/v1").rstrip("/")
    model = os.environ.get("EMBED_MODEL", "BAAI/bge-m3")
    embedding = request(base + "/embeddings", "POST",
        {"model": model, "input": ["计算机组成原理 BGE-M3 真实维度探测"]},
        {"Authorization": "Bearer " + key})["data"][0]["embedding"]
    if len(embedding) != 1024:
        raise RuntimeError(f"BGE-M3 返回 {len(embedding)} 维，期望 1024；未写入 Qdrant")

    qdrant = os.environ.get("QDRANT_URL", "http://127.0.0.1:6333").rstrip("/")
    collection = "probe_bge_m3_1024_" + uuid.uuid4().hex[:12]
    qheaders = {"api-key": os.environ.get("QDRANT_API_KEY", "")} if os.environ.get("QDRANT_API_KEY") else {}
    collection_url = f"{qdrant}/collections/{collection}"
    try:
        request(collection_url, "PUT", {"vectors": {"dense": {"size": 1024, "distance": "Cosine"}},
            "sparse_vectors": {"sparse": {}}}, qheaders)
        request(collection_url + "/points?wait=true", "PUT", {"points": [{"id": 1,
            "vector": {"dense": embedding}, "payload": {"fileId": 1, "courseId": "probe",
            "accessScope": "PUBLIC", "documentVersion": "probe-v1",
            "generationId": "probe-g1", "active": True}}]}, qheaders)
        count = request(collection_url + "/points/count", "POST", {"exact": True}, qheaders)
        actual = int(count.get("result", {}).get("count", 0))
        if actual != 1: raise RuntimeError(f"Qdrant probe point count={actual}, expected 1")
        print(json.dumps({"status": "ok", "model": model, "dimensions": len(embedding),
                          "qdrant_points": actual, "temporary_collection": collection}))
        return 0
    finally:
        try: request(collection_url, "DELETE", headers=qheaders)
        except Exception as exc: print(f"WARNING: temporary collection cleanup failed: {type(exc).__name__}", file=sys.stderr)


if __name__ == "__main__": raise SystemExit(main())
