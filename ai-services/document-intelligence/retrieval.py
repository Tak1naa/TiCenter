"""LlamaIndex nodes/retriever + Qdrant server-side RRF; no business authorization here."""
from __future__ import annotations
import uuid
import httpx
from llama_index.core.retrievers import BaseRetriever
from llama_index.core.schema import TextNode, QueryBundle, NodeWithScore
from llama_index.vector_stores.qdrant import QdrantVectorStore
from qdrant_client import QdrantClient, models as m


class Embeddings:
    def __init__(self, url: str, token: str, dimensions: int):
        self.http = httpx.Client(base_url=url, headers={"X-Api-Token": token}, timeout=30, trust_env=False)
        self.dimensions = dimensions

    def dense(self, texts: list[str]) -> list[list[float]]:
        response = self.http.post('/embed', json={"texts": texts})
        response.raise_for_status()
        vectors = response.json()['embeddings']
        if len(vectors) != len(texts) or any(len(v) != self.dimensions for v in vectors):
            raise ValueError('embedding dimension/count mismatch')
        return vectors

    def sparse(self, texts: list[str]):
        response = self.http.post('/embed_sparse', json={"texts": texts})
        response.raise_for_status()
        vectors = response.json()['embeddings']
        if len(vectors) != len(texts):
            raise ValueError('sparse embedding count mismatch')
        return [v['indices'] for v in vectors], [v['values'] for v in vectors]


class Engine:
    def __init__(self, client: QdrantClient, collection: str, embed: Embeddings, dimensions: int = 1024):
        self.client, self.collection, self.embed, self.dimensions = client, collection, embed, dimensions
        self.store = None

    def initialize(self):
        if not self.client.collection_exists(self.collection):
            self.client.create_collection(self.collection,
                vectors_config={"dense": m.VectorParams(size=self.dimensions, distance=m.Distance.COSINE, on_disk=True)},
                sparse_vectors_config={"sparse": m.SparseVectorParams(index=m.SparseIndexParams(on_disk=True))})
        params = self.client.get_collection(self.collection).config.params
        if not isinstance(params.vectors, dict) or 'dense' not in params.vectors or params.vectors['dense'].size != self.dimensions:
            raise ValueError('Qdrant dense configuration mismatch; use a new collection, never delete the existing one')
        if 'sparse' not in (params.sparse_vectors or {}):
            raise ValueError('Qdrant named sparse vector missing')
        self.store = QdrantVectorStore(client=self.client, collection_name=self.collection,
            enable_hybrid=True, dense_vector_name='dense', sparse_vector_name='sparse', text_key='content',
            sparse_doc_fn=self.embed.sparse, sparse_query_fn=self.embed.sparse, index_doc_id=False)
        for field, kind in [('fileId', m.PayloadSchemaType.INTEGER), ('chunkId', m.PayloadSchemaType.INTEGER),
                            ('generationId', m.PayloadSchemaType.KEYWORD), ('courseId', m.PayloadSchemaType.KEYWORD),
                            ('accessScope', m.PayloadSchemaType.KEYWORD), ('active', m.PayloadSchemaType.BOOL)]:
            self.client.create_payload_index(self.collection, field, field_schema=kind, wait=True)

    @staticmethod
    def generation_filter(file_id: int, generation: str):
        return m.Filter(must=[m.FieldCondition(key='fileId', match=m.MatchValue(value=file_id)),
                              m.FieldCondition(key='generationId', match=m.MatchValue(value=generation))])

    def index(self, chunks: list[dict]) -> int:
        if not chunks:
            return 0
        # All dense embeddings must succeed before a generation is eligible for activation.
        dense = self.embed.dense([c['content'] for c in chunks])
        nodes = []
        for chunk, vector in zip(chunks, dense, strict=True):
            payload = chunk['payload']
            metadata = {**payload, 'chunkId': chunk['chunkId']}
            identifier = str(uuid.uuid5(uuid.NAMESPACE_URL, f"aiqb:{payload['fileId']}:{payload['generationId']}:{chunk['chunkId']}"))
            nodes.append(TextNode(id_=identifier, text=chunk['content'], embedding=vector, metadata=metadata,
                excluded_embed_metadata_keys=list(metadata), excluded_llm_metadata_keys=list(metadata)))
        self.store.add(nodes)
        return len(nodes)

    def search(self, query: str, file_ids: list[int], course: str | None, top_k: int):
        retriever = QdrantRrfRetriever(self, file_ids, course, top_k)
        hits = retriever.retrieve(query)
        return {'hits': [{'chunkId': int(hit.node.metadata['chunkId']), 'content': hit.node.text,
                          'score': hit.score, 'payload': hit.node.metadata,
                          'generationId': hit.node.metadata.get('generationId'),
                          'documentVersion': hit.node.metadata.get('documentVersion'),
                          'accessScope': hit.node.metadata.get('accessScope')} for hit in hits],
                'retrievalMode': retriever.mode, 'degraded': retriever.mode != 'llamaindex-qdrant-hybrid'}


class QdrantRrfRetriever(BaseRetriever):
    def __init__(self, engine: Engine, file_ids: list[int], course: str | None, top_k: int):
        super().__init__()
        self.engine, self.file_ids, self.course, self.top_k = engine, file_ids, course, top_k
        self.mode = 'unavailable'

    def _retrieve(self, query_bundle: QueryBundle) -> list[NodeWithScore]:
        e = self.engine
        must = [m.FieldCondition(key='accessScope', match=m.MatchValue(value='PUBLIC')),
                m.FieldCondition(key='active', match=m.MatchValue(value=True))]
        if self.file_ids:
            must.append(m.FieldCondition(key='fileId', match=m.MatchAny(any=self.file_ids)))
        if self.course:
            must.append(m.FieldCondition(key='courseId', match=m.MatchValue(value=self.course)))
        filters = m.Filter(must=must)
        prefetch = []
        try:
            dense = e.embed.dense([query_bundle.query_str])[0]
            prefetch.append(m.Prefetch(query=dense, using='dense', filter=filters, limit=self.top_k * 2))
        except (httpx.HTTPError, ValueError, KeyError):
            pass
        try:
            indices, values = e.embed.sparse([query_bundle.query_str])
            if indices[0]:
                prefetch.append(m.Prefetch(query=m.SparseVector(indices=indices[0], values=values[0]),
                    using='sparse', filter=filters, limit=self.top_k * 2))
        except (httpx.HTTPError, ValueError, KeyError):
            pass
        if not prefetch:
            return []
        self.mode = 'llamaindex-qdrant-hybrid' if len(prefetch) == 2 else (
            'llamaindex-qdrant-lexical-only' if prefetch[0].using == 'sparse' else 'llamaindex-qdrant-dense-only')
        response = e.client.query_points(e.collection, prefetch=prefetch,
            query=m.FusionQuery(fusion=m.Fusion.RRF), query_filter=filters,
            limit=self.top_k, with_payload=True)
        parsed = e.store.parse_to_query_result(response.points)
        return [NodeWithScore(node=node, score=score) for node, score in zip(parsed.nodes or [], parsed.similarities or [], strict=True)]
