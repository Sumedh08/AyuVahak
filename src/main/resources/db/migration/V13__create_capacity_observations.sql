CREATE TABLE capacity.capacity_observation (
    observation_id UUID PRIMARY KEY,
    facility_issuer TEXT NOT NULL CHECK (length(btrim(facility_issuer)) > 0),
    facility_identifier_type VARCHAR(64) NOT NULL CHECK (facility_identifier_type = 'Facility'),
    facility_identifier TEXT NOT NULL CHECK (length(btrim(facility_identifier)) > 0),
    dimension_code VARCHAR(64) NOT NULL,
    dimension_version BIGINT NOT NULL,
    observation_kind VARCHAR(16) NOT NULL CHECK (observation_kind IN ('VALUE', 'RETRACTION')),
    value_kind VARCHAR(16) CHECK (value_kind IN ('QUANTITY', 'STATE')),
    quantity NUMERIC,
    state_code VARCHAR(64),
    unit_code VARCHAR(32),
    source_code VARCHAR(128) NOT NULL CHECK (source_code ~ '^[A-Za-z][A-Za-z0-9._:-]{0,127}$'),
    source_issuer TEXT NOT NULL CHECK (length(btrim(source_issuer)) > 0),
    source_identifier_type VARCHAR(64) NOT NULL CHECK (source_identifier_type = 'CapacityObservation'),
    source_identifier TEXT NOT NULL CHECK (length(btrim(source_identifier)) > 0),
    reporting_method VARCHAR(32) NOT NULL
        CHECK (reporting_method IN ('MANUAL_ENTRY', 'MANUAL_CORRECTION', 'AUTOMATED')),
    reported_by_kind VARCHAR(16) NOT NULL CHECK (reported_by_kind IN ('HUMAN', 'SERVICE')),
    reported_by_issuer TEXT NOT NULL CHECK (length(btrim(reported_by_issuer)) > 0),
    reported_by_identifier_type VARCHAR(16) NOT NULL,
    reported_by_identifier TEXT NOT NULL CHECK (length(btrim(reported_by_identifier)) > 0),
    observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    source_valid_until TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    future_skewed BOOLEAN NOT NULL,
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    CONSTRAINT capacity_observation_dimension_version_fk
        FOREIGN KEY (dimension_code, dimension_version)
        REFERENCES capacity.capacity_dimension_version (dimension_code, version) ON DELETE RESTRICT,
    CONSTRAINT capacity_observation_source_identity_unique
        UNIQUE (source_code, source_issuer, source_identifier_type, source_identifier),
    CONSTRAINT capacity_observation_actor_type CHECK (
        (reported_by_kind = 'HUMAN' AND reported_by_identifier_type = 'subject') OR
        (reported_by_kind = 'SERVICE' AND reported_by_identifier_type = 'client_id')
    ),
    CONSTRAINT capacity_observation_validity CHECK (
        (source_valid_until IS NULL OR source_valid_until > observed_at) AND
        (valid_until IS NULL OR valid_until > observed_at)
    ),
    CONSTRAINT capacity_observation_value_shape CHECK (
        (observation_kind = 'VALUE' AND (
            (value_kind = 'QUANTITY' AND quantity IS NOT NULL AND quantity >= 0
                AND state_code IS NULL AND unit_code IS NOT NULL
                AND unit_code ~ '^[A-Za-z][A-Za-z0-9._-]{0,31}$' AND valid_until IS NOT NULL)
            OR
            (value_kind = 'STATE' AND quantity IS NULL AND state_code IS NOT NULL
                AND state_code ~ '^[A-Z][A-Z0-9._-]{0,63}$' AND unit_code IS NULL
                AND valid_until IS NOT NULL)
        )) OR
        (observation_kind = 'RETRACTION' AND value_kind IS NULL AND quantity IS NULL
            AND state_code IS NULL AND unit_code IS NULL AND source_valid_until IS NULL
            AND valid_until IS NULL)
    )
);

CREATE TABLE capacity.observation_correction (
    correcting_observation_id UUID PRIMARY KEY
        REFERENCES capacity.capacity_observation (observation_id) ON DELETE RESTRICT,
    superseded_observation_id UUID NOT NULL UNIQUE
        REFERENCES capacity.capacity_observation (observation_id) ON DELETE RESTRICT,
    correction_kind VARCHAR(16) NOT NULL CHECK (correction_kind IN ('CORRECTION', 'RETRACTION')),
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    CHECK (correcting_observation_id <> superseded_observation_id)
);

CREATE INDEX capacity_observation_facility_history_idx
    ON capacity.capacity_observation
        (facility_issuer, facility_identifier, dimension_code, observed_at DESC, received_at DESC);

CREATE INDEX capacity_observation_source_history_idx
    ON capacity.capacity_observation (source_code, source_issuer, source_identifier, received_at DESC);
