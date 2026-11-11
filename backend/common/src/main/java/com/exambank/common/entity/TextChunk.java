package com.exambank.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "text_chunks")
public class TextChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "chunk_hash", length = 64)
    private String chunkHash;

    @Column(name = "milvus_id", length = 100)
    private String milvusId;

    @Column(name = "parent_chunk_id")
    private Long parentChunkId;

    @Column(name = "structure_type", length = 32)
    private String structureType = "paragraph";

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "index_status", length = 20)
    private String indexStatus = "pending";

    @Column(name = "generation_id", length = 64)
    private String generationId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public TextChunk() {}

    public TextChunk(Long id, Long fileId, Integer chunkIndex, String content, Integer pageNumber,
                     String chunkHash, String milvusId, LocalDateTime createdAt) {
        this.id = id;
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.pageNumber = pageNumber;
        this.chunkHash = chunkHash;
        this.milvusId = milvusId;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public String getChunkHash() { return chunkHash; }
    public void setChunkHash(String chunkHash) { this.chunkHash = chunkHash; }
    public String getMilvusId() { return milvusId; }
    public void setMilvusId(String milvusId) { this.milvusId = milvusId; }
    public Long getParentChunkId() { return parentChunkId; }
    public void setParentChunkId(Long parentChunkId) { this.parentChunkId = parentChunkId; }
    public String getStructureType() { return structureType; }
    public void setStructureType(String structureType) { this.structureType = structureType; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
    public String getGenerationId() { return generationId; }
    public void setGenerationId(String generationId) { this.generationId = generationId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Long fileId;
        private Integer chunkIndex;
        private String content;
        private Integer pageNumber;
        private String chunkHash;
        private String milvusId;
        private LocalDateTime createdAt = LocalDateTime.now();

        public Builder id(Long id) { this.id = id; return this; }
        public Builder fileId(Long fileId) { this.fileId = fileId; return this; }
        public Builder chunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder pageNumber(Integer pageNumber) { this.pageNumber = pageNumber; return this; }
        public Builder chunkHash(String chunkHash) { this.chunkHash = chunkHash; return this; }
        public Builder milvusId(String milvusId) { this.milvusId = milvusId; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public TextChunk build() {
            return new TextChunk(id, fileId, chunkIndex, content, pageNumber, chunkHash, milvusId, createdAt);
        }
    }
}
