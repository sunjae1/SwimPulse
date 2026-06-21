DELETE FROM subscriptions
WHERE event_id IS NULL;

ALTER TABLE subscriptions
    MODIFY event_id BIGINT NOT NULL;

ALTER TABLE notifications
    MODIFY status ENUM('FAILED', 'QUEUED', 'SENDING', 'SENT') NOT NULL;

ALTER TABLE notifications
    ADD COLUMN queued_at DATETIME(6) NULL,
    ADD COLUMN processing_started_at DATETIME(6) NULL,
    ADD COLUMN dedupe_key VARCHAR(120) NULL;

UPDATE notifications
SET queued_at = created_at
WHERE queued_at IS NULL;

CREATE UNIQUE INDEX uk_notifications_dedupe_key
    ON notifications (dedupe_key);

CREATE INDEX idx_notifications_status_processing_started_at
    ON notifications (status, processing_started_at);
