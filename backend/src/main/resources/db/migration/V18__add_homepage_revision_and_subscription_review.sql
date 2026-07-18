ALTER TABLE pools
    ADD COLUMN homepage_revision INT NOT NULL DEFAULT 1;

ALTER TABLE pool_notice_sources
    ADD COLUMN homepage_revision INT NOT NULL DEFAULT 1;

ALTER TABLE pool_notices
    ADD COLUMN homepage_revision INT NOT NULL DEFAULT 1;

ALTER TABLE registration_events
    ADD COLUMN source_validity_status ENUM('ACTIVE', 'REVIEW_REQUIRED', 'INVALIDATED') NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN source_changed_at DATETIME(6) NULL,
    ADD COLUMN source_change_reason VARCHAR(500) NULL;

ALTER TABLE subscriptions
    ADD COLUMN review_status ENUM('ACTIVE', 'REVIEW_REQUIRED', 'CONFIRMED', 'INVALIDATED') NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN review_requested_at DATETIME(6) NULL,
    ADD COLUMN reviewed_at DATETIME(6) NULL,
    ADD COLUMN review_reason VARCHAR(500) NULL;

ALTER TABLE notifications
    MODIFY COLUMN type ENUM('REGISTRATION_REMINDER', 'REGISTRATION_OPEN', 'SOURCE_REVIEW_REQUIRED') NOT NULL,
    MODIFY COLUMN status ENUM('FAILED', 'QUEUED', 'SENDING', 'SENT', 'CANCELLED') NOT NULL,
    ADD COLUMN subscription_id BIGINT NULL;

UPDATE notifications notification
JOIN subscriptions subscription
  ON subscription.user_id = notification.user_id
 AND subscription.event_id = notification.event_id
SET notification.subscription_id = subscription.id
WHERE notification.subscription_id IS NULL;

ALTER TABLE notifications
    ADD CONSTRAINT fk_notifications_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE SET NULL;

CREATE INDEX idx_pool_notices_pool_revision_id
    ON pool_notices (pool_id, homepage_revision, id);

CREATE INDEX idx_pool_notice_sources_pool_revision_status
    ON pool_notice_sources (pool_id, homepage_revision, status);

CREATE INDEX idx_subscriptions_event_review_status
    ON subscriptions (event_id, review_status);

CREATE INDEX idx_notifications_subscription_status
    ON notifications (subscription_id, status);
