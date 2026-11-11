package com.exambank.common.repository;

import com.exambank.common.entity.WorkspaceItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceItemRepository extends JpaRepository<WorkspaceItem, Long> {
    Page<WorkspaceItem> findByOwnerOrderByCreatedAtDesc(String owner, Pageable page);
}
