package com.exambank.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Operational trace for one RAG request. API keys and prompt bodies are deliberately excluded. */
@Entity
@Table(name = "chat_request_traces", indexes = {
        @Index(name = "idx_trace_student_created", columnList = "student_no,created_at"),
        @Index(name = "idx_trace_status_created", columnList = "status,created_at")
})
public class ChatRequestTrace {
    @Id
    @Column(name = "request_id", length = 36)
    private String requestId;

    @Column(name = "student_no", length = 32, nullable = false)
    private String studentNo;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "retrieval_mode", length = 32)
    private String retrievalMode;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "document_version", length = 64)
    private String documentVersion;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "queue_wait_ms")
    private Long queueWaitMs;

    @Column(name = "retrieved_count")
    private Integer retrievedCount;

    @Column(name = "input_tokens_estimate")
    private Long inputTokensEstimate;

    @Column(name = "output_tokens_estimate")
    private Long outputTokensEstimate;

    @Column(name = "prefix_cache_candidate")
    private Boolean prefixCacheCandidate;

    @Column(name = "input_tokens_actual")
    private Long inputTokensActual;
    @Column(name = "output_tokens_actual")
    private Long outputTokensActual;
    @Column(name = "provider_cached_tokens")
    private Long providerCachedTokens;
    @Column(name = "usage_source", length = 16)
    private String usageSource;
    @Column(name = "retrieval_cache_hit")
    private Boolean retrievalCacheHit;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "STARTED";

    @Column(name = "references_json", columnDefinition = "TEXT")
    private String referencesJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRetrievalMode() { return retrievalMode; }
    public void setRetrievalMode(String retrievalMode) { this.retrievalMode = retrievalMode; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public Long getQueueWaitMs() { return queueWaitMs; }
    public void setQueueWaitMs(Long queueWaitMs) { this.queueWaitMs = queueWaitMs; }
    public Integer getRetrievedCount() { return retrievedCount; }
    public void setRetrievedCount(Integer retrievedCount) { this.retrievedCount = retrievedCount; }
    public Long getInputTokensEstimate() { return inputTokensEstimate; }
    public void setInputTokensEstimate(Long value) { this.inputTokensEstimate = value; }
    public Long getOutputTokensEstimate() { return outputTokensEstimate; }
    public void setOutputTokensEstimate(Long value) { this.outputTokensEstimate = value; }
    public Boolean getPrefixCacheCandidate() { return prefixCacheCandidate; }
    public void setPrefixCacheCandidate(Boolean value) { this.prefixCacheCandidate = value; }
    public Long getInputTokensActual() { return inputTokensActual; }
    public void setInputTokensActual(Long value) { this.inputTokensActual = value; }
    public Long getOutputTokensActual() { return outputTokensActual; }
    public void setOutputTokensActual(Long value) { this.outputTokensActual = value; }
    public Long getProviderCachedTokens() { return providerCachedTokens; }
    public void setProviderCachedTokens(Long value) { this.providerCachedTokens = value; }
    public String getUsageSource() { return usageSource; }
    public void setUsageSource(String value) { this.usageSource = value; }
    public Boolean getRetrievalCacheHit() { return retrievalCacheHit; }
    public void setRetrievalCacheHit(Boolean value) { this.retrievalCacheHit = value; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReferencesJson() { return referencesJson; }
    public void setReferencesJson(String referencesJson) { this.referencesJson = referencesJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
