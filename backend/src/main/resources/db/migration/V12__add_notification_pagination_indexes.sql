CREATE INDEX idx_notifications_user_created_at
    ON notifications (user_id, created_at DESC, id DESC);

CREATE INDEX idx_notifications_user_read_at
    ON notifications (user_id, read_at);
