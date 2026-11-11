package com.exambank.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Auditable two-phase document/chunk/vector generation. */
@Entity
@Table(name = "document_generations", indexes = {
        @Index(name = "idx_generation_file_status", columnList = "file_id,status"),
        @Index(name = "idx_generation_cleanup", columnList = "status,retired_at")
})
public class DocumentGeneration {
    @Id
    @Column(name = "generation_id", length = 64)
    private String generationId;
    @Column(name = "file_id", nullable = false)
    private Long fileId;
    @Column(name = "document_version", length = 64, nullable = false)
    private String documentVersion;
    @Column(name = "chunker_version", length = 64, nullable = false)
    private String chunkerVersion;
    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;
    @Column(name = "status", length = 24, nullable = false)
    private String status = "STAGING";
    @Column(name = "expected_chunk_count", nullable = false)
    private int expectedChunkCount;
    @Column(name = "indexed_count", nullable = false)
    private int indexedCount;
    @Column(name = "error", length = 1000)
    private String error;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;
    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    public String getGenerationId() { return generationId; }
    public void setGenerationId(String generationId) { this.generationId = generationId; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }
    public String getChunkerVersion() { return chunkerVersion; }
    public void setChunkerVersion(String chunkerVersion) { this.chunkerVersion = chunkerVersion; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getExpectedChunkCount() { return expectedChunkCount; }
    public void setExpectedChunkCount(int expectedChunkCount) { this.expectedChunkCount = expectedChunkCount; }
    public int getIndexedCount() { return indexedCount; }
    public void setIndexedCount(int indexedCount) { this.indexedCount = indexedCount; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(LocalDateTime activatedAt) { this.activatedAt = activatedAt; }
    public LocalDateTime getRetiredAt() { return retiredAt; }
    public void setRetiredAt(LocalDateTime retiredAt) { this.retiredAt = retiredAt; }
}
