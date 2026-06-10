CREATE TABLE notice_registration_periods (
    id BIGINT NOT NULL AUTO_INCREMENT,
    notice_id BIGINT NOT NULL,
    label VARCHAR(255) NULL,
    normalized_label VARCHAR(255) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    period_text VARCHAR(1000) NULL,
    source VARCHAR(100) NULL,
    status ENUM('ACTIVE', 'INACTIVE') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notice_registration_period
        UNIQUE (notice_id, normalized_label, starts_at, ends_at),
    CONSTRAINT fk_notice_registration_period_notice
        FOREIGN KEY (notice_id) REFERENCES pool_notices(id)
);

CREATE INDEX idx_notice_registration_period_notice_status
    ON notice_registration_periods (notice_id, status, starts_at);

ALTER TABLE pool_notices
    ADD COLUMN periods_migrated_at DATETIME(6) NULL,
    ADD COLUMN periods_migration_error VARCHAR(1000) NULL;

ALTER TABLE registration_events
    ADD COLUMN notice_registration_period_id BIGINT NULL,
    ADD CONSTRAINT uk_registration_event_notice_period
        UNIQUE (notice_registration_period_id),
    ADD CONSTRAINT fk_registration_event_notice_period
        FOREIGN KEY (notice_registration_period_id)
        REFERENCES notice_registration_periods(id);
