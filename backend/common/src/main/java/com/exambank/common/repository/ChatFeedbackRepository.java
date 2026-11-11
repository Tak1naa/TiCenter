package com.exambank.common.repository;

import com.exambank.common.entity.ChatFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatFeedbackRepository extends JpaRepository<ChatFeedback, Long> {
    boolean existsByRequestIdAndStudentNo(String requestId, String studentNo);
}
