package com.exambank.common.repository;

import com.exambank.common.entity.ChatRequestTrace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRequestTraceRepository extends JpaRepository<ChatRequestTrace, String> {
}
