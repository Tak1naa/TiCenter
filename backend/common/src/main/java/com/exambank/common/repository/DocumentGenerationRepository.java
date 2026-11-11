package com.exambank.common.repository;

import com.exambank.common.entity.DocumentGeneration;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DocumentGenerationRepository extends JpaRepository<DocumentGeneration, String> {
    Optional<DocumentGeneration> findByFileIdAndStatus(Long fileId, String status);
    List<DocumentGeneration> findTop50ByStatusOrderByRetiredAtAsc(String status);
    boolean existsByFileIdAndStatusIn(Long fileId, java.util.Collection<String> statuses);
}
