package com.exambank.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** AI 按题库文件生成的题目（choice 单选 / judge 判断 / short 简答）。 */
@Entity
@Table(name = "questions", indexes = {
        @Index(name = "idx_question_type", columnList = "question_type"),
        @Index(name = "idx_question_file", columnList = "file_id")
        ,@Index(name = "idx_question_review", columnList = "review_status,created_at")
})
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 来源题库文件（exam_files.id），整册生成时可空。 */
    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "source_file", length = 500)
    private String sourceFile;

    @Column(name = "source_page")
    private Integer sourcePage;

    @Column(name = "question_type", length = 20, nullable = false)
    private String questionType;

    @Column(name = "stem", columnDefinition = "TEXT", nullable = false)
    private String stem;

    /** 选项 JSON 数组（choice）：["A. ...", "B. ..."]；judge/short 为空。 */
    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    @Column(name = "answer", length = 500, nullable = false)
    private String answer;

    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "difficulty", length = 20, nullable = false)
    private String difficulty = "medium";

    @Column(name = "knowledge_point", length = 200)
    private String knowledgePoint;

    @Column(name = "review_status", length = 20, nullable = false)
    private String reviewStatus = "PENDING";

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Question() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public Integer getSourcePage() { return sourcePage; }
    public void setSourcePage(Integer sourcePage) { this.sourcePage = sourcePage; }
    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }
    public String getStem() { return stem; }
    public void setStem(String stem) { this.stem = stem; }
    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public String getKnowledgePoint() { return knowledgePoint; }
    public void setKnowledgePoint(String knowledgePoint) { this.knowledgePoint = knowledgePoint; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
}
