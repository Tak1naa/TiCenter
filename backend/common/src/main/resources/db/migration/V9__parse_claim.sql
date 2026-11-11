ALTER TABLE exam_files
    ADD COLUMN parse_claim_token VARCHAR(64) NULL,
    ADD COLUMN parse_claimed_at DATETIME(6) NULL,
    ADD INDEX idx_exam_file_parse_claim (parse_claim_token, parse_claimed_at);
