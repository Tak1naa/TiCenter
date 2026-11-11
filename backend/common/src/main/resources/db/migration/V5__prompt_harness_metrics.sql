ALTER TABLE chat_request_traces
    ADD COLUMN input_tokens_estimate BIGINT NULL,
    ADD COLUMN output_tokens_estimate BIGINT NULL,
    ADD COLUMN prefix_cache_candidate BOOLEAN NULL,
    ADD INDEX idx_trace_cache_created (prefix_cache_candidate, created_at);
