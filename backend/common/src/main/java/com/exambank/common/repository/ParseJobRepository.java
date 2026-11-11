package com.exambank.common.repository;

import com.exambank.common.entity.ParseJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ParseJobRepository extends JpaRepository<ParseJob, String> {
    boolean existsByFileIdAndStatusIn(Long fileId, Collection<String> statuses);
    List<ParseJob> findTop10ByStatusAndNextRunAtBeforeOrderByCreatedAtAsc(String status, LocalDateTime now);

    @Modifying
    @Transactional
    @Query("update ParseJob p set p.status='RUNNING', p.attempts=p.attempts+1 "
            + "where p.id=:id and p.status='PENDING' and p.nextRunAt <= :now")
    int claim(@Param("id") String id, @Param("now") LocalDateTime now);
}
