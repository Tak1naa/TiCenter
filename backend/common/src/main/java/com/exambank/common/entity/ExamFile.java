package com.exambank.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "exam_files")
public class ExamFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_path", length = 500, nullable = false, unique = true)
    private String filePath;

    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    @Column(name = "file_type", length = 20)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "parse_method", length = 20)
    private String parseMethod;

    @Column(name = "parse_status", length = 20)
    private String parseStatus = "pending";

    @Column(name = "total_pages")
    private Integer totalPages;

    @Column(name = "parsed_pages")
    private Integer parsedPages = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "last_modified")
    private LocalDateTime lastModified;

    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "course_id", length = 64)
    private String courseId = "default";

    @Column(name = "document_version", length = 64)
    private String documentVersion;

    @Column(name = "active_generation_id", length = 64)
    private String activeGenerationId;

    @Column(name = "parse_claim_token", length = 64)
    private String parseClaimToken;

    @Column(name = "parse_claimed_at")
    private LocalDateTime parseClaimedAt;

    @Column(name = "access_scope", length = 32)
    private String accessScope = "PUBLIC";

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public ExamFile() {}

    public ExamFile(Long id, String filePath, String fileName, String fileType, Long fileSize,
                    String parseMethod, String parseStatus, Integer totalPages, Integer parsedPages,
                    String errorMessage, LocalDateTime lastModified, LocalDateTime parsedAt,
                    LocalDateTime createdAt, LocalDateTime updatedAt, String fileHash) {
        this.id = id;
        this.filePath = filePath;
        this.fileName = fileName;
        this.fileType = fileType;
        this.fileSize = fileSize;
        this.parseMethod = parseMethod;
        this.parseStatus = parseStatus != null ? parseStatus : "pending";
        this.totalPages = totalPages;
        this.parsedPages = parsedPages != null ? parsedPages : 0;
        this.errorMessage = errorMessage;
        this.lastModified = lastModified;
        this.parsedAt = parsedAt;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
        this.fileHash = fileHash;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getParseMethod() { return parseMethod; }
    public void setParseMethod(String parseMethod) { this.parseMethod = parseMethod; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public Integer getTotalPages() { return totalPages; }
    public void setTotalPages(Integer totalPages) { this.totalPages = totalPages; }
    public Integer getParsedPages() { return parsedPages; }
    public void setParsedPages(Integer parsedPages) { this.parsedPages = parsedPages; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getLastModified() { return lastModified; }
    public void setLastModified(LocalDateTime lastModified) { this.lastModified = lastModified; }
    public LocalDateTime getParsedAt() { return parsedAt; }
    public void setParsedAt(LocalDateTime parsedAt) { this.parsedAt = parsedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public String getCourseId() { return courseId; }
    public void setCourseId(String courseId) { this.courseId = courseId; }
    public String getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }
    public String getActiveGenerationId() { return activeGenerationId; }
    public void setActiveGenerationId(String activeGenerationId) { this.activeGenerationId = activeGenerationId; }
    public String getParseClaimToken() { return parseClaimToken; }
    public void setParseClaimToken(String parseClaimToken) { this.parseClaimToken = parseClaimToken; }
    public LocalDateTime getParseClaimedAt() { return parseClaimedAt; }
    public void setParseClaimedAt(LocalDateTime parseClaimedAt) { this.parseClaimedAt = parseClaimedAt; }
    public String getAccessScope() { return accessScope; }
    public void setAccessScope(String accessScope) { this.accessScope = accessScope; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private String filePath;
        private String fileName;
        private String fileType;
        private Long fileSize;
        private String parseMethod;
        private String parseStatus = "pending";
        private Integer totalPages;
        private Integer parsedPages = 0;
        private String errorMessage;
        private LocalDateTime lastModified;
        private LocalDateTime parsedAt;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = LocalDateTime.now();
        private String fileHash;

        public Builder id(Long id) { this.id = id; return this; }
        public Builder filePath(String filePath) { this.filePath = filePath; return this; }
        public Builder fileName(String fileName) { this.fileName = fileName; return this; }
        public Builder fileType(String fileType) { this.fileType = fileType; return this; }
        public Builder fileSize(Long fileSize) { this.fileSize = fileSize; return this; }
        public Builder parseMethod(String parseMethod) { this.parseMethod = parseMethod; return this; }
        public Builder parseStatus(String parseStatus) { this.parseStatus = parseStatus; return this; }
        public Builder totalPages(Integer totalPages) { this.totalPages = totalPages; return this; }
        public Builder parsedPages(Integer parsedPages) { this.parsedPages = parsedPages; return this; }
        public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public Builder lastModified(LocalDateTime lastModified) { this.lastModified = lastModified; return this; }
        public Builder parsedAt(LocalDateTime parsedAt) { this.parsedAt = parsedAt; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public Builder fileHash(String fileHash) { this.fileHash = fileHash; return this; }
        public ExamFile build() {
            return new ExamFile(id, filePath, fileName, fileType, fileSize, parseMethod, parseStatus,
                    totalPages, parsedPages, errorMessage, lastModified, parsedAt, createdAt, updatedAt, fileHash);
        }
    }
}
