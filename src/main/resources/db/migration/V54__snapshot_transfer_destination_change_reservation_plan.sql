ALTER TABLE transfer.destination_change_operation
    ADD COLUMN capacity_dimension_code varchar(64),
    ADD COLUMN capacity_unit_code varchar(32),
    ADD COLUMN capacity_quantity numeric(18, 6),
    ADD COLUMN capacity_interval_start timestamptz,
    ADD COLUMN capacity_interval_end timestamptz,
    ADD CONSTRAINT transfer_destination_change_capacity_plan_shape CHECK (
        (capacity_dimension_code IS NULL AND capacity_unit_code IS NULL
            AND capacity_quantity IS NULL AND capacity_interval_start IS NULL
            AND capacity_interval_end IS NULL)
        OR
        (capacity_dimension_code IS NOT NULL AND capacity_unit_code IS NOT NULL
            AND capacity_quantity > 0 AND capacity_interval_start IS NOT NULL
            AND capacity_interval_end > capacity_interval_start)
    );
