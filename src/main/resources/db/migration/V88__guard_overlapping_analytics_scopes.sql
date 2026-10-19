ALTER TABLE analytics.disclosure_reservation
    ADD COLUMN scope_indicator_key VARCHAR(64),
    ADD COLUMN scope_indicator_version INTEGER,
    ADD COLUMN scope_organization_issuer VARCHAR(256),
    ADD COLUMN scope_organization_id UUID,
    ADD COLUMN scope_facility_id UUID,
    ADD COLUMN scope_period_start_utc TIMESTAMPTZ,
    ADD COLUMN scope_period_end_exclusive_utc TIMESTAMPTZ,
    ADD CONSTRAINT analytics_disclosure_reservation_scope_complete CHECK (
        (
            scope_indicator_key IS NULL
            AND scope_indicator_version IS NULL
            AND scope_organization_issuer IS NULL
            AND scope_organization_id IS NULL
            AND scope_facility_id IS NULL
            AND scope_period_start_utc IS NULL
            AND scope_period_end_exclusive_utc IS NULL
        )
        OR (
            scope_indicator_key IS NOT NULL
            AND scope_indicator_version IS NOT NULL
            AND scope_indicator_version > 0
            AND scope_organization_issuer IS NOT NULL
            AND scope_organization_id IS NOT NULL
            AND scope_period_start_utc IS NOT NULL
            AND scope_period_end_exclusive_utc IS NOT NULL
            AND scope_period_start_utc < scope_period_end_exclusive_utc
        )
    );

UPDATE analytics.disclosure_reservation reservation
SET scope_indicator_key = snapshot.indicator_key,
    scope_indicator_version = snapshot.definition_version,
    scope_organization_issuer = snapshot.organization_issuer,
    scope_organization_id = snapshot.organization_id,
    scope_facility_id = snapshot.facility_id,
    scope_period_start_utc = snapshot.period_start_utc,
    scope_period_end_exclusive_utc = snapshot.period_end_exclusive_utc
FROM analytics.dhis2_export_operation operation
JOIN analytics.aggregate_snapshot snapshot ON snapshot.snapshot_id = operation.snapshot_id
WHERE operation.disclosure_reservation_id = reservation.reservation_id;

UPDATE analytics.disclosure_reservation reservation
SET scope_indicator_key = snapshot.indicator_key,
    scope_indicator_version = snapshot.definition_version,
    scope_organization_issuer = snapshot.organization_issuer,
    scope_organization_id = snapshot.organization_id,
    scope_facility_id = snapshot.facility_id,
    scope_period_start_utc = snapshot.period_start_utc,
    scope_period_end_exclusive_utc = snapshot.period_end_exclusive_utc
FROM analytics.federated_aggregate_publication publication
JOIN analytics.aggregate_snapshot snapshot ON snapshot.snapshot_id = publication.source_snapshot_id
WHERE publication.disclosure_reservation_id = reservation.reservation_id
  AND reservation.scope_indicator_key IS NULL;

CREATE INDEX analytics_disclosure_reservation_scope_overlap_idx
    ON analytics.disclosure_reservation (
        scope_organization_issuer,
        scope_organization_id,
        scope_period_start_utc,
        scope_period_end_exclusive_utc,
        state
    )
    INCLUDE (scope_indicator_key, scope_indicator_version, scope_facility_id)
    WHERE scope_indicator_key IS NOT NULL;
