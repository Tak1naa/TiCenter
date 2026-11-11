ALTER TABLE questions
    ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN content_hash VARCHAR(64) NULL,
    ADD INDEX idx_question_review (review_status, created_at),
    ADD INDEX idx_question_content_hash (content_hash);
