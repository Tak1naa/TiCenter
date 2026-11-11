package com.exambank.vector.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;

/** Persistent dense+sparse vector storage backed by Qdrant Query API. */
@Service
public class QdrantVectorStore {
    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private final RestClient client;
    private final String collection;
    private final int denseDimensions;
    private final java.util.concurrent.atomic.AtomicBoolean initialized = new java.util.concurrent.atomic.AtomicBoolean();

    public QdrantVectorStore(@Value("${app.qdrant.url:http://localhost:6333}") String url,
                             @Value("${app.qdrant.collection:exam_chunks_bge_m3_1024_v1}") String collection,
                             @Value("${app.qdrant.api-key:}") String apiKey,
                             @Value("${app.qdrant.dense-dimensions:1024}") int denseDimensions) {
        RestClient.Builder builder = RestClient.builder().baseUrl(url);
        if (apiKey != null && !apiKey.isBlank()) builder.defaultHeader("api-key", apiKey);
        this.client = builder.build();
        this.collection = collection;
        this.denseDimensions = denseDimensions;
    }

    public void ensureCollection() {
        if (initialized.get()) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.get().uri("/collections/{name}", collection)
                    .retrieve().body(Map.class);
            validateCollection(response);
            ensurePayloadIndices();
            initialized.set(true);
            return;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) throw e;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vectors", Map.of("dense", Map.of(
                "size", denseDimensions, "distance", "Cosine", "on_disk", true)));
        body.put("sparse_vectors", Map.of("sparse", Map.of(
                "index", Map.of("on_disk", true))));
        client.put().uri("/collections/{name}", collection)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
        ensurePayloadIndices();
        initialized.set(true);
        log.info("Created Qdrant collection {}", collection);
    }

    private void ensurePayloadIndices() {
        createPayloadIndex("fileId", "integer");
        createPayloadIndex("courseId", "keyword");
        createPayloadIndex("accessScope", "keyword");
        createPayloadIndex("documentVersion", "keyword");
        createPayloadIndex("generationId", "keyword");
        createPayloadIndex("active", "bool");
    }

    @SuppressWarnings("unchecked")
    private void validateCollection(Map<String, Object> response) {
        try {
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            Map<String, Object> config = (Map<String, Object>) result.get("config");
            Map<String, Object> params = (Map<String, Object>) config.get("params");
            Map<String, Object> vectors = (Map<String, Object>) params.get("vectors");
            Map<String, Object> dense = (Map<String, Object>) vectors.get("dense");
            int actual = ((Number) dense.get("size")).intValue();
            if (actual != denseDimensions) {
                throw new IllegalStateException("Qdrant collection '" + collection + "' dense dimension is "
                        + actual + ", expected " + denseDimensions + "; create a new versioned collection");
            }
            Map<String, Object> sparseVectors = (Map<String, Object>) params.get("sparse_vectors");
            if (sparseVectors == null || !sparseVectors.containsKey("sparse"))
                throw new IllegalStateException("Qdrant collection '" + collection + "' has no named sparse vector");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant collection '" + collection
                    + "' does not expose the required named dense vector", e);
        }
    }

    private void createPayloadIndex(String field, String type) {
        try {
            client.put().uri("/collections/{name}/index?wait=true", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("field_name", field, "field_schema", type))
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Qdrant payload index {} was not created: {}", field, e.getMessage());
        }
    }

    @CircuitBreaker(name = "qdrant")
    @Bulkhead(name = "qdrant")
    public void upsert(List<PointData> points) {
        if (points.isEmpty()) return;
        List<Map<String, Object>> payload = new ArrayList<>(points.size());
        for (PointData point : points) {
            Map<String, Object> vectors = new LinkedHashMap<>();
            if (point.dense() != null && !point.dense().isEmpty()) vectors.put("dense", point.dense());
            if (point.sparse() != null && !point.sparse().indices().isEmpty()) {
                vectors.put("sparse", Map.of("indices", point.sparse().indices(), "values", point.sparse().values()));
            }
            payload.add(Map.of("id", point.chunkId(), "vector", vectors, "payload", point.payload()));
        }
        client.put().uri("/collections/{name}/points?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", payload)).retrieve().toBodilessEntity();
    }

    @CircuitBreaker(name = "qdrant")
    @Bulkhead(name = "qdrant")
    @SuppressWarnings("unchecked")
    public SearchResult search(List<Double> dense, SparseVector sparse, List<Long> fileIds,
                               String courseId, int topK) {
        boolean denseAvailable = dense != null && !dense.isEmpty();
        boolean sparseAvailable = sparse != null && !sparse.indices().isEmpty();
        if (!denseAvailable && !sparseAvailable) return new SearchResult(List.of(), "unavailable", true);
        if (denseAvailable && dense.size() != denseDimensions) {
            throw new IllegalStateException("query embedding dimension is " + dense.size()
                    + ", expected " + denseDimensions);
        }

        List<Map<String, Object>> must = new ArrayList<>();
        must.add(Map.of("key", "active", "match", Map.of("value", true)));
        if (fileIds != null && !fileIds.isEmpty())
            must.add(Map.of("key", "fileId", "match", Map.of("any", fileIds)));
        if (courseId != null && !courseId.isBlank())
            must.add(Map.of("key", "courseId", "match", Map.of("value", courseId)));
        // Access scope is server-owned. This store never performs an unscoped student search.
        must.add(Map.of("key", "accessScope", "match", Map.of("value", "PUBLIC")));
        Map<String, Object> filter = must.isEmpty() ? null : Map.of("must", must);
        Map<String, Object> body = new LinkedHashMap<>();
        String mode;
        if (denseAvailable && sparseAvailable) {
            List<Map<String, Object>> prefetch = new ArrayList<>();
            prefetch.add(queryPart(dense, "dense", Math.max(topK, 20), filter));
            prefetch.add(queryPart(Map.of("indices", sparse.indices(), "values", sparse.values()),
                    "sparse", Math.max(topK, 20), filter));
            body.put("prefetch", prefetch);
            body.put("query", Map.of("fusion", "rrf"));
            mode = "qdrant-hybrid";
        } else if (denseAvailable) {
            body.putAll(queryPart(dense, "dense", topK, filter));
            mode = "qdrant-dense";
        } else {
            body.putAll(queryPart(Map.of("indices", sparse.indices(), "values", sparse.values()),
                    "sparse", topK, filter));
            mode = "qdrant-lexical";
        }
        body.put("limit", topK);
        body.put("with_payload", true);

        Map<String, Object> response = client.post()
                .uri("/collections/{name}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(Map.class);
        if (response == null) return new SearchResult(List.of(), mode, !denseAvailable);
        Object result = response.get("result");
        List<Map<String, Object>> points;
        if (result instanceof Map<?, ?> resultMap) {
            Object rawPoints = resultMap.get("points");
            points = rawPoints instanceof List<?> ? (List<Map<String, Object>>) rawPoints : List.of();
        } else if (result instanceof List<?>) {
            points = (List<Map<String, Object>>) result;
        } else {
            points = List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        for (Map<String, Object> point : points) {
            Map<String, Object> pointPayload = (Map<String, Object>) point.getOrDefault("payload", Map.of());
            hits.add(new SearchHit(((Number) point.get("id")).longValue(),
                    String.valueOf(pointPayload.getOrDefault("content", "")),
                    ((Number) point.getOrDefault("score", 0)).doubleValue(), pointPayload));
        }
        return new SearchResult(hits, mode, !denseAvailable);
    }

    private Map<String, Object> queryPart(Object query, String using, int limit, Map<String, Object> filter) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("query", query);
        part.put("using", using);
        part.put("limit", limit);
        if (filter != null) part.put("filter", filter);
        return part;
    }

    public void deleteByChunkIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        client.post().uri("/collections/{name}/points/delete?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("points", chunkIds))
                .retrieve().toBodilessEntity();
    }

    public void setGenerationActive(long fileId, String generationId, boolean active) {
        client.post().uri("/collections/{name}/points/payload?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("payload", Map.of("active", active), "filter", generationFilter(fileId, generationId)))
                .retrieve().toBodilessEntity();
    }

    public void deleteGeneration(long fileId, String generationId) {
        client.post().uri("/collections/{name}/points/delete?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", generationFilter(fileId, generationId)))
                .retrieve().toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    public long countGeneration(long fileId, String generationId) {
        Map<String, Object> response = client.post().uri("/collections/{name}/points/count", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", generationFilter(fileId, generationId), "exact", true))
                .retrieve().body(Map.class);
        Map<String, Object> result = response == null ? Map.of()
                : (Map<String, Object>) response.getOrDefault("result", Map.of());
        return ((Number) result.getOrDefault("count", 0)).longValue();
    }

    @SuppressWarnings("unchecked")
    public boolean verifyGenerationSample(long fileId, String generationId) {
        Map<String, Object> response = client.post().uri("/collections/{name}/points/scroll", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", generationFilter(fileId, generationId), "limit", 3,
                        "with_payload", true, "with_vector", true))
                .retrieve().body(Map.class);
        Map<String, Object> result = response == null ? Map.of()
                : (Map<String, Object>) response.getOrDefault("result", Map.of());
        List<Map<String, Object>> points = (List<Map<String, Object>>) result.getOrDefault("points", List.of());
        if (points.isEmpty()) return false;
        for (Map<String, Object> point : points) {
            Map<String, Object> payload = (Map<String, Object>) point.getOrDefault("payload", Map.of());
            if (!Objects.equals(((Number) payload.getOrDefault("fileId", -1)).longValue(), fileId)
                    || !generationId.equals(payload.get("generationId"))) return false;
            Map<String, Object> vectors = (Map<String, Object>) point.getOrDefault("vector", Map.of());
            Object dense = vectors.get("dense");
            if (dense instanceof List<?> list && list.size() != denseDimensions) return false;
            if (!(dense instanceof List<?>) && !vectors.containsKey("sparse")) return false;
        }
        return true;
    }

    private Map<String, Object> generationFilter(long fileId, String generationId) {
        return Map.of("must", List.of(
                Map.of("key", "fileId", "match", Map.of("value", fileId)),
                Map.of("key", "generationId", "match", Map.of("value", generationId))));
    }

    @SuppressWarnings("unchecked")
    public long size() {
        try {
            Map<String, Object> response = client.get().uri("/collections/{name}", collection)
                    .retrieve().body(Map.class);
            Map<String, Object> result = response == null ? Map.of() : (Map<String, Object>) response.getOrDefault("result", Map.of());
            return ((Number) result.getOrDefault("points_count", 0)).longValue();
        } catch (Exception e) {
            return 0;
        }
    }

    public record SparseVector(List<Integer> indices, List<Double> values) {}
    public record PointData(long chunkId, List<Double> dense, SparseVector sparse, Map<String, Object> payload) {}
    public record SearchHit(long chunkId, String content, double score, Map<String, Object> payload) {}
    public record SearchResult(List<SearchHit> hits, String mode, boolean degraded) {}
}
