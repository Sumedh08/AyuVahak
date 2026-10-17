CREATE TABLE matching.remote_capacity_evidence_projection (
    remote_deployment_id varchar(128) NOT NULL,
    remote_context varchar(32) NOT NULL CHECK (remote_context = 'directory'),
    remote_facility_id uuid NOT NULL,
    dimension_code varchar(64) NOT NULL CHECK (dimension_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    source_sequence bigint NOT NULL CHECK (source_sequence > 0),
    dimension_version bigint NOT NULL CHECK (dimension_version > 0),
    policy_version bigint NOT NULL CHECK (policy_version > 0),
    source_profile_version varchar(64) NOT NULL,
    evidence_id varchar(128) NOT NULL,
    readiness_outcome varchar(24) NOT NULL,
    evidence_freshness varchar(24) NOT NULL,
    source_complete boolean NOT NULL,
    quantity numeric(18, 6),
    state_value varchar(128),
    unit_value varchar(128),
    observed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    evidence_fingerprint char(64) NOT NULL CHECK (evidence_fingerprint ~ '^[0-9a-f]{64}$'),
    source_conflict boolean NOT NULL DEFAULT false,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (remote_deployment_id, remote_context, remote_facility_id, dimension_code),
    CHECK (readiness_outcome IN ('READY', 'NOT_READY', 'CONFLICTED', 'STALE', 'UNKNOWN')),
    CHECK (evidence_freshness IN ('CURRENT', 'STALE', 'EXPIRED', 'UNKNOWN', 'CONFLICTED')),
    CHECK (quantity IS NULL OR quantity >= 0),
    CHECK (valid_until > observed_at)
);

CREATE INDEX remote_capacity_projection_expiry
    ON matching.remote_capacity_evidence_projection (valid_until);
