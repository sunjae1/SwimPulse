CREATE TEMPORARY TABLE pool_notice_rank_tmp AS
SELECT
    ranked.notice_id,
    FIRST_VALUE(ranked.notice_id) OVER (
        PARTITION BY ranked.pool_id, ranked.normalized_url
        ORDER BY
            ranked.has_linked_event DESC,
            ranked.parser_version DESC,
            ranked.extraction_priority DESC,
            ranked.last_analyzed_at DESC,
            ranked.notice_id
    ) AS canonical_notice_id,
    ranked.normalized_url
FROM (
    SELECT
        notice.id AS notice_id,
        notice.pool_id,
        REGEXP_REPLACE(
            SUBSTRING_INDEX(TRIM(notice.url), '#', 1),
            ';jsessionid=[^/?#;]*',
            '',
            1,
            0,
            'i'
        ) AS normalized_url,
        notice.parser_version,
        CASE notice.extraction_status
            WHEN 'EXTRACTED' THEN 2
            WHEN 'LINK_ONLY' THEN 1
            ELSE 0
        END AS extraction_priority,
        notice.last_analyzed_at,
        EXISTS (
            SELECT 1
            FROM notice_registration_periods period
            JOIN registration_events event
                ON event.notice_registration_period_id = period.id
            WHERE period.notice_id = notice.id
        ) AS has_linked_event
    FROM pool_notices notice
) ranked;

UPDATE pool_notices notice
JOIN pool_notice_rank_tmp ranked
    ON ranked.notice_id = notice.id
SET notice.url = ranked.normalized_url;

CREATE TEMPORARY TABLE notice_period_rank_tmp AS
SELECT
    ranked.period_id,
    ranked.target_notice_id,
    FIRST_VALUE(ranked.period_id) OVER (
        PARTITION BY
            ranked.target_notice_id,
            ranked.normalized_label,
            ranked.starts_at,
            ranked.ends_at
        ORDER BY
            ranked.has_linked_event DESC,
            ranked.belongs_to_target_notice DESC,
            ranked.status_priority DESC,
            ranked.period_id
    ) AS canonical_period_id
FROM (
    SELECT
        period.id AS period_id,
        notice_rank.canonical_notice_id AS target_notice_id,
        period.normalized_label,
        period.starts_at,
        period.ends_at,
        period.notice_id = notice_rank.canonical_notice_id AS belongs_to_target_notice,
        period.status = 'ACTIVE' AS status_priority,
        EXISTS (
            SELECT 1
            FROM registration_events event
            WHERE event.notice_registration_period_id = period.id
        ) AS has_linked_event
    FROM notice_registration_periods period
    JOIN pool_notice_rank_tmp notice_rank
        ON notice_rank.notice_id = period.notice_id
) ranked;

CREATE TEMPORARY TABLE registration_event_period_rank_tmp AS
SELECT
    ranked.event_id,
    ranked.target_period_id,
    FIRST_VALUE(ranked.event_id) OVER (
        PARTITION BY ranked.target_period_id
        ORDER BY
            ranked.already_uses_target_period DESC,
            ranked.event_id
    ) AS canonical_event_id
FROM (
    SELECT
        event.id AS event_id,
        period_rank.canonical_period_id AS target_period_id,
        event.notice_registration_period_id = period_rank.canonical_period_id
            AS already_uses_target_period
    FROM registration_events event
    JOIN notice_period_rank_tmp period_rank
        ON period_rank.period_id = event.notice_registration_period_id
) ranked;

DELETE subscription
FROM subscriptions subscription
JOIN registration_event_period_rank_tmp event_rank
    ON event_rank.event_id = subscription.event_id
    AND event_rank.event_id <> event_rank.canonical_event_id
JOIN subscriptions retained_subscription
    ON retained_subscription.user_id = subscription.user_id
    AND retained_subscription.event_id = event_rank.canonical_event_id;

UPDATE subscriptions subscription
JOIN registration_event_period_rank_tmp event_rank
    ON event_rank.event_id = subscription.event_id
    AND event_rank.event_id <> event_rank.canonical_event_id
SET subscription.event_id = event_rank.canonical_event_id;

UPDATE notifications notification
JOIN registration_event_period_rank_tmp event_rank
    ON event_rank.event_id = notification.event_id
    AND event_rank.event_id <> event_rank.canonical_event_id
SET notification.event_id = event_rank.canonical_event_id;

DELETE registration_event
FROM registration_events registration_event
JOIN registration_event_period_rank_tmp event_rank
    ON event_rank.event_id = registration_event.id
    AND event_rank.event_id <> event_rank.canonical_event_id;

UPDATE registration_events registration_event
JOIN registration_event_period_rank_tmp event_rank
    ON event_rank.event_id = registration_event.id
    AND event_rank.event_id = event_rank.canonical_event_id
SET registration_event.notice_registration_period_id = event_rank.target_period_id;

DELETE period
FROM notice_registration_periods period
JOIN notice_period_rank_tmp period_rank
    ON period_rank.period_id = period.id
    AND period_rank.period_id <> period_rank.canonical_period_id;

UPDATE notice_registration_periods period
JOIN notice_period_rank_tmp period_rank
    ON period_rank.period_id = period.id
    AND period_rank.period_id = period_rank.canonical_period_id
SET period.notice_id = period_rank.target_notice_id;

DELETE notice
FROM pool_notices notice
JOIN pool_notice_rank_tmp notice_rank
    ON notice_rank.notice_id = notice.id
    AND notice_rank.notice_id <> notice_rank.canonical_notice_id;

ALTER TABLE pool_notices
    ADD CONSTRAINT uk_pool_notices_pool_url UNIQUE (pool_id, url);

DROP TEMPORARY TABLE registration_event_period_rank_tmp;
DROP TEMPORARY TABLE notice_period_rank_tmp;
DROP TEMPORARY TABLE pool_notice_rank_tmp;
