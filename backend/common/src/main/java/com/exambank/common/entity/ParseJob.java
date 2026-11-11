package com.exambank.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "parse_jobs", indexes = {
        @Index(name = "idx_parse_job_status_next", columnList = "status,next_run_at"),
        @Index(name = "idx_parse_job_file", columnList = "file_id")
})
public class ParseJob {
    @Id
    @Column(name = "id", length = 36)
    private String id;
    @Column(name = "file_id", nullable = false)
    private Long fileId;
    @Column(name = "status", length = 20, nullable = false)
    private String status = "PENDING";
    @Column(name = "attempts", nullable = false)
    private int attempts;
    @Column(name = "next_run_at", nullable = false)
    private LocalDateTime nextRunAt = LocalDateTime.now();
    @Column(name = "last_error", length = 1000)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate void updateTimestamp() { updatedAt = LocalDateTime.now(); }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
