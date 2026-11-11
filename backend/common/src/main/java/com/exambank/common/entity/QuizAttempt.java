package com.exambank.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 学生答题记录。错题本 = 每人每题最新一次作答为错的题。 */
@Entity
@Table(name = "quiz_attempts", indexes = {
        @Index(name = "idx_attempt_student", columnList = "student_id"),
        @Index(name = "idx_attempt_question", columnList = "question_id")
})
public class QuizAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "selected_answer", length = 500)
    private String selectedAnswer;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(name = "attempted_at")
    private LocalDateTime attemptedAt = LocalDateTime.now();

    public QuizAttempt() {}

    public QuizAttempt(Long studentId, Long questionId, String selectedAnswer, boolean correct) {
        this.studentId = studentId;
        this.questionId = questionId;
        this.selectedAnswer = selectedAnswer;
        this.correct = correct;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getSelectedAnswer() { return selectedAnswer; }
    public void setSelectedAnswer(String selectedAnswer) { this.selectedAnswer = selectedAnswer; }
    public boolean isCorrect() { return correct; }
    public void setCorrect(boolean correct) { this.correct = correct; }
    public LocalDateTime getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(LocalDateTime attemptedAt) { this.attemptedAt = attemptedAt; }
}
