CREATE TABLE analytics.computation_operation (
    request_id UUID PRIMARY KEY,
    request_fingerprint CHAR(64) NOT NULL
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    indicator_key VARCHAR(64) NOT NULL
        CHECK (indicator_key ~ '^[A-Z0-9][A-Z0-9._-]{0,63}$'),
    definition_version INTEGER NOT NULL CHECK (definition_version > 0),
    period_start_local DATE NOT NULL,
    period_end_exclusive_local DATE NOT NULL,
    referral_source_cutoff TIMESTAMPTZ NOT NULL,
    organization_issuer VARCHAR(256) NOT NULL,
    organization_reference_type VARCHAR(64) NOT NULL CHECK (organization_reference_type = 'Organization'),
    organization_id UUID NOT NULL,
    requester_issuer VARCHAR(256) NOT NULL,
    requester_client_id VARCHAR(128) NOT NULL
        CHECK (requester_client_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}$'),
    correlation_id VARCHAR(128) NOT NULL
        CHECK (correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    operation_state VARCHAR(32) NOT NULL
        CHECK (operation_state IN ('PENDING', 'PROCESSING', 'RETRY_PENDING', 'COMPLETED', 'FAILED_PERMANENT')),
    attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 8),
    available_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    snapshot_id UUID REFERENCES analytics.aggregate_snapshot(snapshot_id),
    failure_class VARCHAR(48)
        CHECK (failure_class IS NULL OR failure_class ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (period_start_local < period_end_exclusive_local),
    CHECK ((operation_state = 'COMPLETED') = (snapshot_id IS NOT NULL)),
    CHECK ((operation_state = 'PROCESSING') = (lease_until IS NOT NULL))
);

CREATE INDEX computation_operation_pending_idx
    ON analytics.computation_operation (available_at, created_at, request_id)
    WHERE operation_state IN ('PENDING', 'RETRY_PENDING');

CREATE INDEX computation_operation_expired_lease_idx
    ON analytics.computation_operation (lease_until, created_at, request_id)
    WHERE operation_state = 'PROCESSING';
