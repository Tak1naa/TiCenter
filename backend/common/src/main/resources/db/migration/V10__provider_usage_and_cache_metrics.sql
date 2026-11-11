ALTER TABLE chat_request_traces
    ADD COLUMN input_tokens_actual BIGINT NULL,
    ADD COLUMN output_tokens_actual BIGINT NULL,
    ADD COLUMN provider_cached_tokens BIGINT NULL,
    ADD COLUMN usage_source VARCHAR(16) NULL,
    ADD COLUMN retrieval_cache_hit BOOLEAN NULL,
    ADD INDEX idx_trace_retrieval_cache_created (retrieval_cache_hit, created_at);
