-- Align the legacy init script with Hibernate's Boolean mapping on MySQL.
-- Existing 0/1 values are preserved by the in-place type conversion.
ALTER TABLE quiz_attempts
    MODIFY COLUMN is_correct BIT(1) NOT NULL DEFAULT b'0';
