ALTER TABLE exam_files
    ADD COLUMN course_id VARCHAR(64) NOT NULL DEFAULT 'default',
    ADD COLUMN document_version VARCHAR(64) NULL,
    ADD COLUMN access_scope VARCHAR(32) NOT NULL DEFAULT 'PUBLIC',
    ADD INDEX idx_exam_course_scope (course_id, access_scope);

UPDATE exam_files SET document_version = COALESCE(file_hash, CAST(id AS CHAR))
WHERE document_version IS NULL;

ALTER TABLE text_chunks
    ADD COLUMN parent_chunk_id BIGINT NULL,
    ADD COLUMN structure_type VARCHAR(32) NOT NULL DEFAULT 'paragraph',
    ADD COLUMN embedding_model VARCHAR(128) NULL,
    ADD COLUMN index_status VARCHAR(20) NOT NULL DEFAULT 'pending',
    ADD INDEX idx_chunk_page_order (file_id, page_number, chunk_index),
    ADD INDEX idx_chunk_index_status (index_status);

ALTER TABLE chat_history
    ADD INDEX idx_chat_session_created (session_id, created_at);

ALTER TABLE quiz_attempts
    ADD INDEX idx_attempt_student_question_time (student_id, question_id, attempted_at),
    ADD CONSTRAINT fk_attempt_student FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_attempt_question FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE;

CREATE TABLE chat_request_traces (
    request_id VARCHAR(36) PRIMARY KEY,
    student_no VARCHAR(32) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    retrieval_mode VARCHAR(32),
    provider VARCHAR(64),
    model VARCHAR(128),
    document_version VARCHAR(64),
    latency_ms BIGINT,
    queue_wait_ms BIGINT,
    retrieved_count INT,
    status VARCHAR(20) NOT NULL DEFAULT 'STARTED',
    references_json TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6),
    INDEX idx_trace_student_created (student_no, created_at),
    INDEX idx_trace_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    student_no VARCHAR(32) NOT NULL,
    helpful BOOLEAN NOT NULL,
    error_type VARCHAR(32),
    comment_text VARCHAR(1000),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_feedback_request_student (request_id, student_no),
    INDEX idx_feedback_request (request_id),
    INDEX idx_feedback_created (created_at),
    CONSTRAINT fk_feedback_trace FOREIGN KEY (request_id) REFERENCES chat_request_traces(request_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
