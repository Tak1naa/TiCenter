from __future__ import annotations
import os
import secrets
from contextlib import asynccontextmanager
from typing import Literal
from fastapi import FastAPI, Depends, Header, HTTPException
from pydantic import BaseModel, Field, ConfigDict
from qdrant_client import QdrantClient, models as m
from llama_index.core.node_parser import SentenceSplitter
from retrieval import Engine, Embeddings

TOKEN = os.environ.get('API_SERVICE_TOKEN', '')
engine: Engine | None = None

def auth(x_api_token: str = Header(default='')):
    if not TOKEN:
        raise HTTPException(503, 'service identity is not configured')
    if not secrets.compare_digest(x_api_token, TOKEN):
        raise HTTPException(401, 'invalid service identity')

@asynccontextmanager
async def lifespan(app):
    global engine
    dims = int(os.environ.get('EMBEDDING_DIMENSIONS', '1024'))
    client = QdrantClient(url=os.environ.get('QDRANT_URL', 'http://127.0.0.1:6333'),
        api_key=os.environ.get('QDRANT_API_KEY') or None, timeout=15)
    engine = Engine(client, os.environ.get('QDRANT_COLLECTION', 'exam_chunks_llamaindex_bge_m3_1024_v1'),
        Embeddings(os.environ.get('EMBEDDING_SERVICE_URL', 'http://127.0.0.1:8002'), TOKEN, dims), dims)
    engine.initialize()
    yield
    engine.embed.http.close()
    client.close()

app = FastAPI(title='Document Intelligence', lifespan=lifespan)

class Strict(BaseModel):
    model_config = ConfigDict(extra='forbid')

class Search(Strict):
    query: str = Field(min_length=1, max_length=2000)
    fileIds: list[int] = Field(default_factory=list, max_length=100)
    courseId: str | None = Field(default=None, max_length=64)
    topK: int = Field(default=20, ge=1, le=100)

class Chunk(Strict):
    chunkId: int = Field(gt=0)
    content: str = Field(min_length=1, max_length=8000)
    payload: dict

class Index(Strict):
    chunks: list[Chunk] = Field(max_length=64)

class Generation(Strict):
    fileId: int = Field(gt=0)
    generationId: str = Field(min_length=1, max_length=64)
    expectedCount: int | None = Field(default=None, ge=1)

class Split(Strict):
    text: str = Field(max_length=200000)

@app.get('/health')
def health():
    return {'status': 'ok', 'engine': 'llamaindex-qdrant', 'chunkerVersion': 'llamaindex-sentence-600-75-v1'}

@app.post('/internal/retrieval/search', dependencies=[Depends(auth)])
def search(req: Search):
    if len(set(req.fileIds)) != len(req.fileIds) or any(i <= 0 for i in req.fileIds):
        raise HTTPException(400, 'INVALID_FILE_SELECTION')
    return engine.search(req.query, req.fileIds, req.courseId, req.topK)

@app.post('/internal/index', dependencies=[Depends(auth)])
def index(req: Index):
    for chunk in req.chunks:
        if not {'fileId', 'generationId', 'documentVersion', 'accessScope', 'active'} <= chunk.payload.keys():
            raise HTTPException(400, 'incomplete generation payload')
    return {'indexed': engine.index([c.model_dump() for c in req.chunks])}

@app.post('/internal/split', dependencies=[Depends(auth)])
def split(req: Split):
    splitter = SentenceSplitter(chunk_size=600, chunk_overlap=75)
    # The Java caller invokes this per page, so no node ever crosses a page boundary.
    return {'chunks': [{'text': text, 'type': 'paragraph'} for text in splitter.split_text(req.text)],
            'chunkerVersion': 'llamaindex-sentence-600-75-v1'}

@app.post('/internal/generations/{action}', dependencies=[Depends(auth)])
def generation(action: Literal['activate', 'cleanup'], req: Generation):
    filters = engine.generation_filter(req.fileId, req.generationId)
    count = engine.client.count(engine.collection, count_filter=filters, exact=True).count
    if action == 'cleanup':
        engine.client.delete(engine.collection, points_selector=m.FilterSelector(filter=filters), wait=True)
        return {'deleted': True, 'generationId': req.generationId}
    if req.expectedCount is None or count != req.expectedCount:
        raise HTTPException(409, 'VECTOR_COUNT_MISMATCH')
    sample, _ = engine.client.scroll(engine.collection, scroll_filter=filters, limit=3, with_vectors=True)
    if not sample or any(len((p.vector or {}).get('dense', [])) != engine.dimensions for p in sample):
        raise HTTPException(409, 'VECTOR_SAMPLE_INVALID')
    engine.client.set_payload(engine.collection, payload={'active': True}, points=filters, wait=True)
    return {'active': True, 'count': count}

@app.post('/internal/delete', dependencies=[Depends(auth)])
def delete(req: dict):
    ids = req.get('chunkIds', [])
    if not ids or len(ids) > 1000 or any(type(i) is not int or i <= 0 for i in ids):
        raise HTTPException(400, 'invalid chunk IDs')
    engine.client.delete(engine.collection, points_selector=m.FilterSelector(filter=m.Filter(must=[
        m.FieldCondition(key='chunkId', match=m.MatchAny(any=ids))])), wait=True)
    return {'deleted': len(ids)}

@app.get('/internal/status', dependencies=[Depends(auth)])
def status():
    return {'qdrantVectors': engine.client.count(engine.collection, exact=True).count}

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host=os.environ.get('BIND_HOST', '127.0.0.1'), port=8004)
