ALTER TABLE pool_notices
    ADD COLUMN ocr_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN ocr_requested_at DATETIME(6) NULL,
    ADD COLUMN ocr_started_at DATETIME(6) NULL,
    ADD COLUMN ocr_completed_at DATETIME(6) NULL;
