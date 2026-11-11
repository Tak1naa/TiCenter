package com.exambank.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** User-confirmed derived content. Never a published question or a mutation of the source. */
@Entity
@Table(name = "workspace_items")
public class WorkspaceItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, length = 64) public String owner;
    @Column(nullable = false, length = 16) public String kind;
    @Column(nullable = false, length = 160) public String title;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT") public String content;
    @Column(name = "references_json", nullable = false, columnDefinition = "JSON") public String referencesJson;
    @Column(nullable = false, length = 16) public String status = "DRAFT";
    @Column(name = "created_at", nullable = false) public LocalDateTime createdAt = LocalDateTime.now();
}
