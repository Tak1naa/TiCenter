package com.exambank.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_feedback", indexes = {
        @Index(name = "idx_feedback_request", columnList = "request_id"),
        @Index(name = "idx_feedback_created", columnList = "created_at")
})
public class ChatFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", length = 36, nullable = false)
    private String requestId;

    @Column(name = "student_no", length = 32, nullable = false)
    private String studentNo;

    @Column(name = "helpful", nullable = false)
    private boolean helpful;

    @Column(name = "error_type", length = 32)
    private String errorType;

    @Column(name = "comment_text", length = 1000)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public boolean isHelpful() { return helpful; }
    public void setHelpful(boolean helpful) { this.helpful = helpful; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
