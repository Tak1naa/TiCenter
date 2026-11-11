CREATE TABLE document_generations (
    generation_id VARCHAR(64) PRIMARY KEY,
    file_id BIGINT NOT NULL,
    document_version VARCHAR(64) NOT NULL,
    chunker_version VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(128) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'STAGING',
    expected_chunk_count INT NOT NULL DEFAULT 0,
    indexed_count INT NOT NULL DEFAULT 0,
    error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    activated_at DATETIME(6) NULL,
    retired_at DATETIME(6) NULL,
    INDEX idx_generation_file_status (file_id, status),
    INDEX idx_generation_cleanup (status, retired_at),
    CONSTRAINT chk_generation_status CHECK (status IN ('STAGING','INDEXED','ACTIVE','RETIRED','FAILED','CLEANUP_PENDING')),
    CONSTRAINT fk_generation_file FOREIGN KEY (file_id) REFERENCES exam_files(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE exam_files ADD COLUMN active_generation_id VARCHAR(64) NULL;
ALTER TABLE text_chunks
    ADD COLUMN generation_id VARCHAR(64) NULL,
    ADD INDEX idx_chunk_file_generation_status (file_id, generation_id, index_status);

INSERT INTO document_generations (
    generation_id, file_id, document_version, chunker_version, embedding_model,
    status, expected_chunk_count, indexed_count, activated_at
)
SELECT CONCAT('legacy-', f.id), f.id, COALESCE(f.document_version, f.file_hash, CAST(f.id AS CHAR)),
       'legacy', NULL, 'ACTIVE', COUNT(c.id),
       SUM(CASE WHEN c.index_status = 'indexed' THEN 1 ELSE 0 END), CURRENT_TIMESTAMP(6)
FROM exam_files f LEFT JOIN text_chunks c ON c.file_id = f.id
GROUP BY f.id, f.document_version, f.file_hash;

UPDATE exam_files SET active_generation_id = CONCAT('legacy-', id)
WHERE active_generation_id IS NULL;
UPDATE text_chunks SET generation_id = CONCAT('legacy-', file_id)
WHERE generation_id IS NULL;
-- The versioned Qdrant collection is new; every legacy chunk must be reindexed into it.
UPDATE text_chunks SET index_status = 'stale';

ALTER TABLE text_chunks MODIFY generation_id VARCHAR(64) NOT NULL;
ALTER TABLE text_chunks
    ADD CONSTRAINT fk_chunk_generation FOREIGN KEY (generation_id)
    REFERENCES document_generations(generation_id) ON DELETE CASCADE;

ALTER TABLE exam_files
    ADD CONSTRAINT fk_exam_active_generation FOREIGN KEY (active_generation_id)
    REFERENCES document_generations(generation_id) ON DELETE SET NULL;
