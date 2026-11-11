package com.exambank.common.repository;

import com.exambank.common.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    List<Question> findTop200ByOrderByCreatedAtDesc();
    List<Question> findTop200ByReviewStatusOrderByCreatedAtDesc(String reviewStatus);
    boolean existsByContentHash(String contentHash);

    List<Question> findByFileIdOrderByCreatedAtDesc(Long fileId);
}
