package com.exambank.vector.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.time.Duration;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;

@Service
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    /** 服务间共享 token（与 Python 服务鉴权一致） */
    private static final String API_SERVICE_TOKEN = System.getenv("API_SERVICE_TOKEN") != null
            ? System.getenv("API_SERVICE_TOKEN") : "";

    private final RestTemplate restTemplate = createRestTemplate();
    private final Cache<String, List<Double>> denseQueryCache = Caffeine.newBuilder()
            .maximumSize(2048).expireAfterWrite(Duration.ofMinutes(10)).recordStats().build();
    private final Cache<String, SparseVector> sparseQueryCache = Caffeine.newBuilder()
            .maximumSize(2048).expireAfterWrite(Duration.ofMinutes(10)).recordStats().build();

    private static RestTemplate createRestTemplate() {
        RestTemplate rt = new RestTemplate();
        if (!API_SERVICE_TOKEN.isEmpty()) {
            rt.getInterceptors().add((req, body, execution) -> {
                req.getHeaders().set("X-Api-Token", API_SERVICE_TOKEN);
                return execution.execute(req, body);
            });
        }
        return rt;
    }

    @Value("${app.embedding.service-url:http://localhost:8002}")
    private String embeddingServiceUrl;

    public record EmbedResponse(List<List<Double>> embeddings, int dimensions) {}
    public record SparseVector(List<Integer> indices, List<Double> values) {}
    public record SparseEmbedResponse(List<SparseVector> embeddings, String model) {}

    @CircuitBreaker(name = "embedding", fallbackMethod = "denseFallback")
    @Bulkhead(name = "embedding")
    public List<List<Double>> embed(List<String> texts) {
        var resp = restTemplate.postForObject(
                    embeddingServiceUrl + "/embed",
                    Map.of("texts", texts),
                    EmbedResponse.class
            );
        return resp != null ? resp.embeddings() : List.of();
    }

    @CircuitBreaker(name = "embedding", fallbackMethod = "queryFallback")
    @Bulkhead(name = "embedding")
    public List<Double> embedQuery(String text) {
        String key = queryKey(text);
        List<Double> cached = denseQueryCache.getIfPresent(key);
        if (cached != null) return cached;
        List<List<Double>> vectors = embed(List.of(text));
        List<Double> result = vectors.isEmpty() ? List.of() : List.copyOf(vectors.get(0));
        if (!result.isEmpty()) denseQueryCache.put(key, result);
        return result;
    }

    @CircuitBreaker(name = "sparseEmbedding", fallbackMethod = "sparseFallback")
    @Bulkhead(name = "sparseEmbedding")
    public List<SparseVector> embedSparse(List<String> texts) {
        var resp = restTemplate.postForObject(
                    embeddingServiceUrl + "/embed_sparse", Map.of("texts", texts), SparseEmbedResponse.class);
        return resp != null ? resp.embeddings() : List.of();
    }

    @CircuitBreaker(name = "sparseEmbedding", fallbackMethod = "sparseQueryFallback")
    @Bulkhead(name = "sparseEmbedding")
    public SparseVector embedSparseQuery(String text) {
        String key = queryKey(text);
        SparseVector cached = sparseQueryCache.getIfPresent(key);
        if (cached != null) return cached;
        List<SparseVector> vectors = embedSparse(List.of(text));
        SparseVector result = vectors.isEmpty() ? new SparseVector(List.of(), List.of()) : vectors.get(0);
        if (!result.indices().isEmpty()) sparseQueryCache.put(key, result);
        return result;
    }

    private List<List<Double>> denseFallback(List<String> texts, Throwable error) {
        log.warn("Dense embedding degraded: {}", error.getMessage());
        return List.of();
    }

    private List<SparseVector> sparseFallback(List<String> texts, Throwable error) {
        log.warn("Sparse embedding degraded: {}", error.getMessage());
        return List.of();
    }

    private List<Double> queryFallback(String text, Throwable error) {
        log.warn("Dense query embedding degraded: {}", error.getMessage());
        return List.of();
    }

    private SparseVector sparseQueryFallback(String text, Throwable error) {
        log.warn("Sparse query embedding degraded: {}", error.getMessage());
        return new SparseVector(List.of(), List.of());
    }

    private String queryKey(String text) {
        String normalized = text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return Integer.toHexString(normalized.hashCode());
        }
    }
}
