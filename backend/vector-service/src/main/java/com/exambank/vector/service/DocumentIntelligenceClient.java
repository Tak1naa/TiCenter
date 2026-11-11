package com.exambank.vector.service;

import com.exambank.common.entity.TextChunk;
import com.exambank.common.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Transport only. Business metadata comes from MySQL, never from an AI response. */
@Service
public class DocumentIntelligenceClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExamFileRepository files;
    private final DocumentGenerationRepository generations;
    private final String baseUrl;
    private final boolean enabled;
    public DocumentIntelligenceClient(ExamFileRepository files, DocumentGenerationRepository generations,
            @Value("${app.retrieval.backend:llamaindex}") String backend,
            @Value("${app.intelligence.service-url:http://localhost:8004}") String baseUrl) {
        this.files = files; this.generations = generations; this.baseUrl = baseUrl;
        enabled = "llamaindex".equals(backend);
    }
    public boolean enabled() { return enabled; }

    public Map<String, Object> post(String path, Object body) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Token", Objects.toString(System.getenv("API_SERVICE_TOKEN"), ""))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Document intelligence HTTP " + response.statusCode());
            return mapper.readValue(response.body(), new TypeReference<>() {});
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Document intelligence unavailable", e);
        }
    }

    public int index(List<TextChunk> chunks) {
        int indexed = 0;
        for (int start = 0; start < chunks.size(); start += 32) {
            List<Map<String, Object>> batch = new ArrayList<>();
            for (TextChunk chunk : chunks.subList(start, Math.min(start + 32, chunks.size()))) {
                var file = files.findById(chunk.getFileId()).orElseThrow();
                var generation = generations.findById(chunk.getGenerationId()).orElseThrow();
                if (!Objects.equals(generation.getFileId(), file.getId())) throw new IllegalStateException("generation owner mismatch");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("fileId", file.getId()); payload.put("fileName", file.getFileName());
                payload.put("courseId", Objects.toString(file.getCourseId(), "default"));
                payload.put("generationId", generation.getGenerationId()); payload.put("documentVersion", generation.getDocumentVersion());
                payload.put("accessScope", file.getAccessScope());
                payload.put("active", Objects.equals(file.getActiveGenerationId(), chunk.getGenerationId()));
                payload.put("page", chunk.getPageNumber()); payload.put("chunkIndex", chunk.getChunkIndex());
                payload.put("structureType", chunk.getStructureType()); payload.put("content", chunk.getContent());
                batch.add(Map.of("chunkId", chunk.getId(), "content", chunk.getContent(), "payload", payload));
            }
            var result = post("/internal/index", Map.of("chunks", batch));
            int count = ((Number) result.getOrDefault("indexed", 0)).intValue();
            if (count != batch.size()) throw new IllegalStateException("incomplete generation index");
            indexed += count;
        }
        return indexed;
    }
}
