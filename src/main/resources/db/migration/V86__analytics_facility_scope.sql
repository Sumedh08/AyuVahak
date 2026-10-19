ALTER TABLE analytics.aggregate_snapshot
    ADD COLUMN facility_id UUID;

ALTER TABLE analytics.computation_operation
    ADD COLUMN facility_id UUID;

ALTER TABLE analytics.dhis2_mapping_version
    ADD COLUMN facility_id UUID;

ALTER TABLE analytics.indicator_definition_dimension
    DROP CONSTRAINT IF EXISTS indicator_definition_dimension_dimension_key_check,
    ADD CONSTRAINT analytics_indicator_definition_dimension_key CHECK (
        dimension_key IN ('ORGANIZATION', 'FACILITY', 'SOURCE_ATTRIBUTION')
    );

CREATE INDEX analytics_aggregate_facility_scope_period_idx
    ON analytics.aggregate_snapshot (
        organization_id, facility_id, indicator_key, definition_version,
        period_start_utc, period_end_exclusive_utc
    );

CREATE INDEX analytics_dhis2_mapping_facility_idx
    ON analytics.dhis2_mapping_version (
        organization_id, facility_id, indicator_key, indicator_version,
        effective_from, effective_until
    );
