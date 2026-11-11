package com.exambank.common.repository;

import com.exambank.common.entity.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    List<QuizAttempt> findByStudentIdOrderByAttemptedAtDesc(Long studentId);
}
