CREATE TABLE workspace_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    references_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL,
    INDEX idx_workspace_owner_created (owner, created_at)
);
