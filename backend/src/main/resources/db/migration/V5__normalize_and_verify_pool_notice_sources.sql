ALTER TABLE pools
    ADD COLUMN last_notice_discovery_at DATETIME(6) NULL;

ALTER TABLE pool_notice_sources
    ADD COLUMN failure_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_error VARCHAR(1000) NULL,
    ADD COLUMN last_success_at DATETIME(6) NULL;

ALTER TABLE pool_notice_sources
    MODIFY COLUMN status ENUM('ACTIVE', 'CANDIDATE', 'VERIFIED', 'INACTIVE', 'FAILED') NOT NULL;

UPDATE pool_notice_sources
SET source_url = REGEXP_REPLACE(
        SUBSTRING_INDEX(TRIM(source_url), '#', 1),
        ';jsessionid=[^/?#;]*',
        '',
        1,
        0,
        'i'
    ),
    status = 'CANDIDATE',
    failure_count = 0,
    last_error = NULL,
    last_success_at = NULL;

ALTER TABLE pool_notice_sources
    MODIFY COLUMN status ENUM('CANDIDATE', 'VERIFIED', 'INACTIVE', 'FAILED') NOT NULL;

DELETE duplicate_source
FROM pool_notice_sources duplicate_source
JOIN pool_notice_sources retained_source
  ON retained_source.pool_id = duplicate_source.pool_id
 AND retained_source.source_url = duplicate_source.source_url
 AND retained_source.id < duplicate_source.id;

ALTER TABLE pool_notice_sources
    ADD CONSTRAINT uk_pool_notice_sources_pool_url UNIQUE (pool_id, source_url);

CREATE INDEX idx_pool_notice_sources_status_scan
    ON pool_notice_sources (status, last_scanned_at, pool_id);
