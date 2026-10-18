CREATE TABLE analytics.aggregate_snapshot_series (
    series_id UUID PRIMARY KEY,
    indicator_key VARCHAR(64) NOT NULL,
    definition_version INTEGER NOT NULL,
    organization_issuer VARCHAR(256) NOT NULL,
    organization_resource_type VARCHAR(64) NOT NULL CHECK (organization_resource_type = 'Organization'),
    organization_id UUID NOT NULL,
    period_start_local DATE NOT NULL,
    period_end_exclusive_local DATE NOT NULL,
    period_start_utc TIMESTAMPTZ NOT NULL,
    period_end_exclusive_utc TIMESTAMPTZ NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    time_zone_rules_version VARCHAR(32) NOT NULL,
    current_revision_number INTEGER NOT NULL CHECK (current_revision_number > 0),
    current_snapshot_id UUID NOT NULL REFERENCES analytics.aggregate_snapshot (snapshot_id) ON DELETE RESTRICT,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PROVISIONAL', 'FINALIZED')),
    correction_window_ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (
        indicator_key,
        definition_version,
        organization_issuer,
        organization_resource_type,
        organization_id,
        period_start_local,
        period_end_exclusive_local,
        period_start_utc,
        period_end_exclusive_utc,
        time_zone_id,
        time_zone_rules_version
    ),
    CONSTRAINT analytics_snapshot_series_period_nonempty CHECK (
        period_start_local < period_end_exclusive_local
        AND period_start_utc < period_end_exclusive_utc
    ),
    FOREIGN KEY (indicator_key, definition_version)
        REFERENCES analytics.indicator_definition_version (indicator_key, definition_version)
        ON DELETE RESTRICT
);

CREATE TABLE analytics.aggregate_snapshot_revision (
    revision_id UUID PRIMARY KEY,
    series_id UUID NOT NULL REFERENCES analytics.aggregate_snapshot_series (series_id) ON DELETE RESTRICT,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    snapshot_id UUID NOT NULL UNIQUE REFERENCES analytics.aggregate_snapshot (snapshot_id) ON DELETE RESTRICT,
    previous_snapshot_id UUID,
    operation_id UUID NOT NULL UNIQUE,
    operation_fingerprint CHAR(64) NOT NULL CHECK (operation_fingerprint ~ '^[0-9a-f]{64}$'),
    cause VARCHAR(32) NOT NULL CHECK (
        cause IN ('INITIAL', 'LATE_OWNER_FACT', 'OWNER_RESTATEMENT', 'SOURCE_DATA_ERROR', 'CALCULATION_ERROR')
    ),
    authorized_correction BOOLEAN NOT NULL DEFAULT FALSE,
    latest_source_cutoff TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL,
    audit_record_id UUID,
    UNIQUE (series_id, revision_number),
    UNIQUE (series_id, snapshot_id),
    CONSTRAINT analytics_snapshot_revision_chain_shape CHECK (
        (revision_number = 1 AND previous_snapshot_id IS NULL AND cause = 'INITIAL')
        OR
        (revision_number > 1 AND previous_snapshot_id IS NOT NULL AND cause <> 'INITIAL')
    ),
    FOREIGN KEY (series_id, previous_snapshot_id)
        REFERENCES analytics.aggregate_snapshot_revision (series_id, snapshot_id)
        ON DELETE RESTRICT
);

CREATE TABLE analytics.aggregate_snapshot_finalization (
    operation_id UUID PRIMARY KEY,
    series_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    reason VARCHAR(32) NOT NULL CHECK (
        reason IN ('PERIOD_CLOSED', 'CORRECTION_WINDOW_ELAPSED', 'OPERATOR_REQUEST')
    ),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    finalized_at TIMESTAMPTZ NOT NULL,
    audit_record_id UUID,
    UNIQUE (series_id, snapshot_id),
    FOREIGN KEY (series_id, snapshot_id)
        REFERENCES analytics.aggregate_snapshot_revision (series_id, snapshot_id)
        ON DELETE RESTRICT
);

CREATE INDEX analytics_snapshot_revision_series_idx
    ON analytics.aggregate_snapshot_revision (series_id, revision_number DESC);
