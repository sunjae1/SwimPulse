CREATE INDEX idx_pools_normalized_name
    ON pools (normalized_name);

CREATE INDEX idx_pools_normalized_road_name_address
    ON pools (normalized_road_name_address);

CREATE INDEX idx_pools_normalized_lot_number_address
    ON pools (normalized_lot_number_address);
