package com.exambank.vector.service;

import com.exambank.common.entity.ExamFile;
import com.exambank.common.entity.TextChunk;
import com.exambank.common.repository.ExamFileRepository;
import com.exambank.common.repository.TextChunkRepository;
import com.exambank.common.entity.DocumentGeneration;
import com.exambank.common.repository.DocumentGenerationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class VectorIndexService {
    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);
    private static final int BATCH_SIZE = 32;

    private final TextChunkRepository chunkRepository;
    private final ExamFileRepository fileRepository;
    private final EmbeddingClient embeddingClient;
    private final QdrantVectorStore vectorStore;
    private final DocumentGenerationRepository generationRepository;
    private final String embeddingModel;
    private final int denseDimensions;
    @org.springframework.beans.factory.annotation.Autowired
    private DocumentIntelligenceClient intelligence;

    public VectorIndexService(TextChunkRepository chunkRepository,
                              ExamFileRepository fileRepository,
                              EmbeddingClient embeddingClient,
                              QdrantVectorStore vectorStore,
                              DocumentGenerationRepository generationRepository,
                              @Value("${app.embedding.model:BAAI/bge-m3}") String embeddingModel,
                              @Value("${app.qdrant.dense-dimensions:1024}") int denseDimensions) {
        this.chunkRepository = chunkRepository;
        this.fileRepository = fileRepository;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.generationRepository = generationRepository;
        this.embeddingModel = embeddingModel;
        this.denseDimensions = denseDimensions;
    }

    public int indexAll() {
        if (intelligence != null && intelligence.enabled()) {
            int count = 0;
            int page = 0;
            org.springframework.data.domain.Page<TextChunk> batch;
            do {
                batch = chunkRepository.findAll(org.springframework.data.domain.PageRequest.of(page++, 128,
                        org.springframework.data.domain.Sort.by("id")));
                List<TextChunk> active = batch.getContent().stream().filter(chunk -> fileRepository.findById(chunk.getFileId())
                        .map(f -> Objects.equals(f.getActiveGenerationId(), chunk.getGenerationId())).orElse(false)).toList();
                count += index(active);
            } while (batch.hasNext());
            return count;
        }
        vectorStore.ensureCollection();
        int total = 0;
        while (true) {
            List<TextChunk> batch = chunkRepository
                    .findTop500ByIndexStatusInOrderByIdAsc(List.of("pending", "stale"));
            if (batch.isEmpty()) return total;
            total += index(batch);
        }
    }

    public int index(List<TextChunk> chunks) {
        if (chunks.isEmpty()) return 0;
        if (intelligence != null && intelligence.enabled()) {
            int count = intelligence.index(chunks);
            chunks.forEach(chunk -> { chunk.setIndexStatus("indexed"); chunk.setEmbeddingModel(embeddingModel); });
            chunkRepository.saveAll(chunks);
            return count;
        }
        vectorStore.ensureCollection();
        Map<Long, ExamFile> files = new HashMap<>();
        fileRepository.findAllById(chunks.stream().map(TextChunk::getFileId).distinct().toList())
                .forEach(file -> files.put(file.getId(), file));
        Map<String, DocumentGeneration> generations = new HashMap<>();
        generationRepository.findAllById(chunks.stream().map(TextChunk::getGenerationId)
                .filter(Objects::nonNull).distinct().toList())
                .forEach(generation -> generations.put(generation.getGenerationId(), generation));
        int indexed = 0;
        for (int offset = 0; offset < chunks.size(); offset += BATCH_SIZE) {
            List<TextChunk> batch = chunks.subList(offset, Math.min(offset + BATCH_SIZE, chunks.size()));
            List<String> texts = batch.stream().map(TextChunk::getContent).toList();
            List<List<Double>> dense = embeddingClient.embed(texts);
            List<EmbeddingClient.SparseVector> sparse = embeddingClient.embedSparse(texts);
            List<QdrantVectorStore.PointData> points = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                TextChunk chunk = batch.get(i);
                List<Double> denseVector = dense.size() == batch.size() ? dense.get(i) : List.of();
                if (!denseVector.isEmpty() && denseVector.size() != denseDimensions) {
                    throw new IllegalStateException("embedding dimension mismatch: got " + denseVector.size()
                            + ", expected " + denseDimensions + " for " + embeddingModel);
                }
                EmbeddingClient.SparseVector sparseVector = sparse.size() == batch.size()
                        ? sparse.get(i) : new EmbeddingClient.SparseVector(List.of(), List.of());
                if (denseVector.isEmpty() && sparseVector.indices().isEmpty()) continue;
                ExamFile file = files.get(chunk.getFileId());
                DocumentGeneration generation = generations.get(chunk.getGenerationId());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", chunk.getContent());
                payload.put("fileId", chunk.getFileId());
                payload.put("page", chunk.getPageNumber() == null ? 1 : chunk.getPageNumber());
                payload.put("chunkIndex", chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex());
                payload.put("structureType", chunk.getStructureType() == null ? "paragraph" : chunk.getStructureType());
                if (file != null) {
                    payload.put("filePath", file.getFilePath());
                    payload.put("fileName", file.getFileName());
                    payload.put("courseId", value(file.getCourseId(), "default"));
                    payload.put("documentVersion", generation != null
                            ? generation.getDocumentVersion()
                            : value(file.getDocumentVersion(), value(file.getFileHash(), "unknown")));
                    payload.put("accessScope", value(file.getAccessScope(), "PUBLIC"));
                }
                payload.put("generationId", value(chunk.getGenerationId(), "legacy-" + chunk.getFileId()));
                payload.put("active", file != null
                        && Objects.equals(file.getActiveGenerationId(), chunk.getGenerationId()));
                points.add(new QdrantVectorStore.PointData(chunk.getId(), denseVector,
                        new QdrantVectorStore.SparseVector(sparseVector.indices(), sparseVector.values()), payload));
                chunk.setMilvusId(null);
                chunk.setIndexStatus("indexed");
                chunk.setEmbeddingModel(denseVector.isEmpty() ? "Qdrant/bm25" : embeddingModel);
            }
            try {
                vectorStore.upsert(points);
                indexed += points.size();
                chunkRepository.saveAll(batch);
            } catch (Exception e) {
                batch.forEach(chunk -> chunk.setIndexStatus("failed"));
                chunkRepository.saveAll(batch);
                log.error("Qdrant index batch failed at {}: {}", offset, e.getMessage());
            }
        }
        return indexed;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
