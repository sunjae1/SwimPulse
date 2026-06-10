ALTER TABLE pool_notices
    ADD COLUMN parser_version INT NOT NULL DEFAULT 0,
    ADD COLUMN last_analyzed_at DATETIME(6) NULL;
