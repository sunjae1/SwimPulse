ALTER TABLE registration_events
    ADD COLUMN notice_url VARCHAR(1000) NULL;

UPDATE registration_events event
JOIN notice_registration_periods period
  ON period.id = event.notice_registration_period_id
JOIN pool_notices notice
  ON notice.id = period.notice_id
SET event.notice_url = notice.url
WHERE event.notice_url IS NULL;
