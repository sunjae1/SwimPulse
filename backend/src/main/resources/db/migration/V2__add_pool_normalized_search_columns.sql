ALTER TABLE pools
    ADD COLUMN normalized_name VARCHAR(100) NOT NULL DEFAULT '',
    ADD COLUMN normalized_lot_number_address VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN normalized_road_name_address VARCHAR(255) NOT NULL DEFAULT '';

UPDATE pools
SET normalized_name = LOWER(
        REPLACE(
            REGEXP_REPLACE(
                REGEXP_REPLACE(COALESCE(name, ''), '<[^>]*>', ''),
                '[[:space:]]+',
                ''
            ),
            '수영장',
            ''
        )
    ),
    normalized_lot_number_address = LOWER(
        REPLACE(
            REGEXP_REPLACE(
                REGEXP_REPLACE(COALESCE(lot_number_address, ''), '<[^>]*>', ''),
                '[[:space:]]+',
                ''
            ),
            '수영장',
            ''
        )
    ),
    normalized_road_name_address = LOWER(
        REPLACE(
            REGEXP_REPLACE(
                REGEXP_REPLACE(COALESCE(road_name_address, ''), '<[^>]*>', ''),
                '[[:space:]]+',
                ''
            ),
            '수영장',
            ''
        )
    );
