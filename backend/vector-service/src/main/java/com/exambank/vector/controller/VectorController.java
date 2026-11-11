package com.exambank.vector.controller;

import com.exambank.common.entity.TextChunk;
import com.exambank.common.repository.TextChunkRepository;
import com.exambank.vector.service.EmbeddingClient;
import com.exambank.vector.service.QdrantVectorStore;
import com.exambank.vector.service.VectorIndexService;
import com.exambank.vector.service.RerankerClient;
import com.exambank.common.repository.ExamFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vector")
@PreAuthorize("hasRole('SERVICE')")
public class VectorController {

    private static final Logger log = LoggerFactory.getLogger(VectorController.class);

    private static final int BATCH_SIZE = 32;

    private final TextChunkRepository textChunkRepository;
    private final EmbeddingClient embeddingClient;
    private final QdrantVectorStore vectorStore;
    private final VectorIndexService indexService;
    private final ExamFileRepository examFileRepository;
    private final RerankerClient rerankerClient;
    @org.springframework.beans.factory.annotation.Autowired
    private com.exambank.vector.service.DocumentIntelligenceClient intelligence;

    public VectorController(TextChunkRepository textChunkRepository,
                            EmbeddingClient embeddingClient,
                            QdrantVectorStore vectorStore,
                            VectorIndexService indexService,
                            ExamFileRepository examFileRepository,
                            RerankerClient rerankerClient) {
        this.textChunkRepository = textChunkRepository;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.indexService = indexService;
        this.examFileRepository = examFileRepository;
        this.rerankerClient = rerankerClient;
    }

    @PostMapping("/index-all")
    public ResponseEntity<Map<String, Object>> indexAll() {
        long total = textChunkRepository.count();
        int indexed = indexService.indexAll();
        return ResponseEntity.ok(Map.of("total", total, "indexed", indexed));
    }

    @PostMapping("/index-chunks")
    public ResponseEntity<Map<String, Object>> indexChunks(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) request.getOrDefault("chunkIds", List.of());
        List<Long> ids = rawIds.stream().map(Number::longValue).distinct().limit(1000).toList();
        List<TextChunk> chunks = textChunkRepository.findAllById(ids);
        int indexed = indexService.index(chunks);
        return ResponseEntity.ok(Map.of("requested", ids.size(), "indexed", indexed));
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, Object> request) {
        String query = String.valueOf(request.getOrDefault("query", ""));
        int requestedTopK = request.containsKey("topK") ? ((Number) request.get("topK")).intValue() : 5;
        final int topK = Math.min(100, Math.max(1, requestedTopK));

        @SuppressWarnings("unchecked")
        List<Number> rawFileIds = (List<Number>) request.getOrDefault("fileIds", List.of());
        List<Long> fileIds = rawFileIds.stream().map(Number::longValue).toList();
        boolean selectionProvided = Boolean.TRUE.equals(request.get("selectionProvided"));
        String courseId = request.get("courseId") == null ? null : String.valueOf(request.get("courseId"));
        if (selectionProvided && fileIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_FILE_SELECTION", "hits", List.of()));
        }
        var resolvedFiles = fileIds.isEmpty() ? List.<com.exambank.common.entity.ExamFile>of()
                : examFileRepository.findAllById(fileIds);
        boolean invalid = !fileIds.isEmpty() && (resolvedFiles.size() != fileIds.size()
                || new java.util.HashSet<>(fileIds).size() != fileIds.size()
                || resolvedFiles.stream().anyMatch(f -> !"PUBLIC".equals(f.getAccessScope())));
        if (invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_FILE_SELECTION", "hits", List.of()));
        }
        if (courseId != null && !courseId.isBlank()) {
            if (!fileIds.isEmpty() && resolvedFiles.stream().anyMatch(f -> !courseId.equals(f.getCourseId()))) {
                return ResponseEntity.ok(Map.of("hits", List.of(), "retrievalMode", "qdrant-filtered", "degraded", false));
            }
            if (fileIds.isEmpty() && examFileRepository.findByCourseIdAndAccessScope(courseId, "PUBLIC").isEmpty()) {
                return ResponseEntity.ok(Map.of("hits", List.of(), "retrievalMode", "qdrant-filtered", "degraded", false));
            }
        }
        if (query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "EMPTY_QUERY", "hits", List.of()));
        }
        if (intelligence != null && intelligence.enabled()) {
            Map<String, Object> validated = new java.util.LinkedHashMap<>();
            validated.put("query", query); validated.put("fileIds", fileIds);
            validated.put("courseId", courseId); validated.put("topK", topK);
            Map<String, Object> response = intelligence.post("/internal/retrieval/search", validated);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hits = (List<Map<String, Object>>) response.getOrDefault("hits", List.of());
            // Qdrant payload is only a candidate. Check current MySQL authority again.
            List<Map<String, Object>> valid = hits.stream().filter(hit -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) hit.getOrDefault("payload", Map.of());
                Object id = payload.get("fileId");
                if (!(id instanceof Number n)) return false;
                var file = examFileRepository.findById(n.longValue()).orElse(null);
                return file != null && "PUBLIC".equals(file.getAccessScope())
                        && (fileIds.isEmpty() || fileIds.contains(file.getId()))
                        && (courseId == null || courseId.isBlank() || courseId.equals(file.getCourseId()))
                        && java.util.Objects.equals(file.getActiveGenerationId(), payload.get("generationId"));
            }).toList();
            response.put("hits", valid);
            return ResponseEntity.ok(response);
        }
        // Nothing external is called until caller-owned selectors have been validated fail-closed.
        vectorStore.ensureCollection();
        List<Double> dense = embeddingClient.embedQuery(query);
        EmbeddingClient.SparseVector sparse = embeddingClient.embedSparseQuery(query);
        var rawSearch = vectorStore.search(dense,
                new QdrantVectorStore.SparseVector(sparse.indices(), sparse.values()),
                fileIds, courseId, Math.min(100, topK * 3));
        Map<Long, com.exambank.common.entity.ExamFile> activeFiles = new java.util.HashMap<>();
        List<Long> hitFileIds = rawSearch.hits().stream().map(hit -> hit.payload().get("fileId"))
                .filter(Number.class::isInstance).map(Number.class::cast).map(Number::longValue).distinct().toList();
        examFileRepository.findAllById(hitFileIds).forEach(file -> activeFiles.put(file.getId(), file));
        List<QdrantVectorStore.SearchHit> validHits = rawSearch.hits().stream().filter(hit -> {
            Object rawFileId = hit.payload().get("fileId");
            if (!(rawFileId instanceof Number n)) return false;
            var file = activeFiles.get(n.longValue());
            String generationId = String.valueOf(hit.payload().getOrDefault("generationId", ""));
            return file != null && "PUBLIC".equals(file.getAccessScope())
                    && generationId.equals(file.getActiveGenerationId());
        }).limit(Math.max(topK, 20)).toList();
        var search = new QdrantVectorStore.SearchResult(validHits, rawSearch.mode(), rawSearch.degraded());
        var reranked = rerankerClient.rerank(query, search.hits());

        List<Map<String, Object>> result = new ArrayList<>();
        for (var hit : reranked.hits()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("chunkId", hit.chunkId());
            row.put("content", hit.content());
            row.put("score", hit.score());
            row.put("rrfScore", hit.payload().getOrDefault("rrfScore", hit.score()));
            row.put("rerankScore", hit.payload().get("rerankScore"));
            row.put("generationId", hit.payload().get("generationId"));
            row.put("documentVersion", hit.payload().get("documentVersion"));
            row.put("accessScope", hit.payload().get("accessScope"));
            row.put("payload", hit.payload());
            result.add(row);
        }
        String retrievalMode = reranked.applied() ? search.mode() + "+rerank" : search.mode();
        return ResponseEntity.ok(Map.of("hits", result, "retrievalMode", retrievalMode,
                "degraded", search.degraded()));
    }

    /** 删除 chunk 向量（文件删除时由 chat-service 通知）。 */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> request) {
        if (intelligence != null && intelligence.enabled())
            return ResponseEntity.ok(intelligence.post("/internal/delete", request));
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) request.getOrDefault("chunkIds", List.of());
        List<Long> chunkIds = rawIds.stream().map(Number::longValue).toList();
        vectorStore.deleteByChunkIds(chunkIds);
        return ResponseEntity.ok(Map.of("deleted", chunkIds.size()));
    }

    @PostMapping("/generations/activate")
    public ResponseEntity<Map<String, Object>> activateGeneration(@RequestBody Map<String, Object> request) {
        if (intelligence != null && intelligence.enabled())
            return ResponseEntity.ok(intelligence.post("/internal/generations/activate", request));
        long fileId = ((Number) request.get("fileId")).longValue();
        String generationId = String.valueOf(request.get("generationId"));
        long count = vectorStore.countGeneration(fileId, generationId);
        Number expected = (Number) request.getOrDefault("expectedCount", count);
        if (count != expected.longValue()) {
            return ResponseEntity.status(409).body(Map.of("error", "VECTOR_COUNT_MISMATCH",
                    "expected", expected.longValue(), "actual", count));
        }
        if (!vectorStore.verifyGenerationSample(fileId, generationId)) {
            return ResponseEntity.status(409).body(Map.of("error", "VECTOR_SAMPLE_INVALID",
                    "generationId", generationId));
        }
        vectorStore.setGenerationActive(fileId, generationId, true);
        return ResponseEntity.ok(Map.of("generationId", generationId, "count", count, "active", true));
    }

    @PostMapping("/generations/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupGeneration(@RequestBody Map<String, Object> request) {
        long fileId = ((Number) request.get("fileId")).longValue();
        String generationId = String.valueOf(request.get("generationId"));
        if (examFileRepository.findById(fileId).map(f -> generationId.equals(f.getActiveGenerationId())).orElse(false))
            return ResponseEntity.status(409).body(Map.of("error", "CANNOT_DELETE_ACTIVE_GENERATION"));
        if (intelligence != null && intelligence.enabled())
            return ResponseEntity.ok(intelligence.post("/internal/generations/cleanup", request));
        vectorStore.setGenerationActive(fileId, generationId, false);
        vectorStore.deleteGeneration(fileId, generationId);
        return ResponseEntity.ok(Map.of("generationId", generationId, "deleted", true));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        long total = textChunkRepository.count();
        // SQL 聚合（替代 findAll + Java 过滤）
        long indexed = textChunkRepository.countByIndexStatus("indexed");
        return ResponseEntity.ok(Map.of("totalChunks", total, "indexedChunks", indexed,
                "pendingChunks", total - indexed, "qdrantVectors", vectorStore.size()));
    }
}
