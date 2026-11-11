package com.exambank.vector.service;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

/** Optional OpenAI-style /rerank adapter. Failure always falls back to Qdrant RRF order. */
@Service
public class RerankerClient {
    private static final Logger log = LoggerFactory.getLogger(RerankerClient.class);
    private final RestClient client;
    private final boolean enabled;
    private final String model;
    private final int outputCount;

    public RerankerClient(@Value("${app.reranker.url:}") String url,
                          @Value("${app.reranker.api-key:}") String apiKey,
                          @Value("${app.reranker.model:BAAI/bge-reranker-v2-m3}") String model,
                          @Value("${app.reranker.enabled:false}") boolean enabled,
                          @Value("${app.reranker.output-count:8}") int outputCount) {
        this.enabled = enabled && url != null && !url.isBlank();
        this.model = model;
        this.outputCount = Math.max(1, Math.min(outputCount, 20));
        RestClient.Builder builder = RestClient.builder().baseUrl(url == null ? "http://127.0.0.1" : url);
        if (apiKey != null && !apiKey.isBlank()) builder.defaultHeader("Authorization", "Bearer " + apiKey);
        this.client = builder.build();
    }

    public Result rerank(String query, List<QdrantVectorStore.SearchHit> hits) {
        if (!enabled || hits.size() < 2) return new Result(hits, false);
        try {
            return call(query, hits);
        } catch (Exception e) {
            log.warn("Reranker unavailable; preserving RRF order: {}", e.getMessage());
            return new Result(hits, false);
        }
    }

    @CircuitBreaker(name = "reranker")
    @Bulkhead(name = "reranker")
    @SuppressWarnings("unchecked")
    protected Result call(String query, List<QdrantVectorStore.SearchHit> hits) {
        List<String> documents = hits.stream().map(QdrantVectorStore.SearchHit::content).toList();
        Map<String, Object> response = client.post().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "query", query, "documents", documents,
                        "top_n", Math.min(outputCount, documents.size()), "return_documents", false))
                .retrieve().body(Map.class);
        List<Map<String, Object>> scores = response == null ? List.of()
                : (List<Map<String, Object>>) response.getOrDefault("results", List.of());
        return mapScores(hits, scores);
    }

    /** Maps provider indices back to stable chunk IDs; package-visible for a deterministic contract test. */
    Result mapScores(List<QdrantVectorStore.SearchHit> hits, List<Map<String, Object>> scores) {
        List<QdrantVectorStore.SearchHit> reordered = new ArrayList<>();
        for (Map<String, Object> score : scores) {
            int index = ((Number) score.getOrDefault("index", -1)).intValue();
            if (index < 0 || index >= hits.size()) continue;
            QdrantVectorStore.SearchHit hit = hits.get(index);
            double relevance = ((Number) score.getOrDefault("relevance_score", hit.score())).doubleValue();
            Map<String, Object> payload = new LinkedHashMap<>(hit.payload());
            payload.put("rrfScore", hit.score());
            payload.put("rerankScore", relevance);
            reordered.add(new QdrantVectorStore.SearchHit(hit.chunkId(), hit.content(), relevance, payload));
        }
        return reordered.isEmpty() ? new Result(hits, false) : new Result(reordered, true);
    }

    public record Result(List<QdrantVectorStore.SearchHit> hits, boolean applied) {}
}
