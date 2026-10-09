CREATE SCHEMA capacity;

CREATE TABLE capacity.capacity_dimension (
    dimension_code VARCHAR(64) PRIMARY KEY
        CHECK (dimension_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    current_version BIGINT NOT NULL CHECK (current_version > 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE capacity.capacity_dimension_version (
    revision_id UUID NOT NULL UNIQUE,
    dimension_code VARCHAR(64) NOT NULL
        REFERENCES capacity.capacity_dimension (dimension_code) ON DELETE RESTRICT,
    version BIGINT NOT NULL CHECK (version > 0),
    display_name VARCHAR(200) NOT NULL CHECK (length(btrim(display_name)) BETWEEN 1 AND 200),
    meaning VARCHAR(1000) NOT NULL CHECK (length(btrim(meaning)) BETWEEN 1 AND 1000),
    value_kind VARCHAR(16) NOT NULL CHECK (value_kind IN ('QUANTITY', 'STATE')),
    unit_code VARCHAR(32),
    minimum_value NUMERIC,
    maximum_value NUMERIC,
    used_for_readiness BOOLEAN NOT NULL,
    reservable BOOLEAN NOT NULL,
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    changed_by_kind VARCHAR(16) NOT NULL CHECK (changed_by_kind IN ('HUMAN', 'SERVICE')),
    changed_by_issuer TEXT NOT NULL CHECK (length(btrim(changed_by_issuer)) > 0),
    changed_by_identifier_type VARCHAR(16) NOT NULL,
    changed_by_identifier TEXT NOT NULL CHECK (length(btrim(changed_by_identifier)) > 0),
    changed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (dimension_code, version),
    CONSTRAINT capacity_dimension_kind_shape CHECK (
        (value_kind = 'QUANTITY' AND unit_code IS NOT NULL
            AND unit_code ~ '^[A-Za-z][A-Za-z0-9._-]{0,31}$')
        OR (value_kind = 'STATE' AND unit_code IS NULL AND minimum_value IS NULL AND maximum_value IS NULL)
    ),
    CONSTRAINT capacity_dimension_range CHECK (
        minimum_value IS NULL OR maximum_value IS NULL OR minimum_value <= maximum_value
    ),
    CONSTRAINT capacity_dimension_nonnegative_range CHECK (
        (minimum_value IS NULL OR minimum_value >= 0)
        AND (maximum_value IS NULL OR maximum_value >= 0)
    ),
    CONSTRAINT capacity_dimension_reservable_quantity CHECK (
        NOT reservable OR value_kind = 'QUANTITY'
    ),
    CONSTRAINT capacity_dimension_actor_type CHECK (
        (changed_by_kind = 'HUMAN' AND changed_by_identifier_type = 'subject') OR
        (changed_by_kind = 'SERVICE' AND changed_by_identifier_type = 'client_id')
    ),
    CONSTRAINT capacity_dimension_version_unique UNIQUE (dimension_code, version, revision_id)
);

ALTER TABLE capacity.capacity_dimension
    ADD CONSTRAINT capacity_dimension_current_version_fk
        FOREIGN KEY (dimension_code, current_version)
        REFERENCES capacity.capacity_dimension_version (dimension_code, version)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE capacity.capacity_dimension_state (
    dimension_code VARCHAR(64) NOT NULL,
    dimension_version BIGINT NOT NULL,
    state_code VARCHAR(64) NOT NULL CHECK (state_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    PRIMARY KEY (dimension_code, dimension_version, state_code),
    FOREIGN KEY (dimension_code, dimension_version)
        REFERENCES capacity.capacity_dimension_version (dimension_code, version)
        ON DELETE RESTRICT
);

CREATE TABLE capacity.capacity_policy (
    dimension_code VARCHAR(64) PRIMARY KEY
        REFERENCES capacity.capacity_dimension (dimension_code) ON DELETE RESTRICT,
    current_version BIGINT NOT NULL CHECK (current_version > 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE capacity.capacity_policy_version (
    revision_id UUID NOT NULL UNIQUE,
    dimension_code VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    dimension_version BIGINT NOT NULL,
    maximum_observation_age_seconds BIGINT NOT NULL CHECK (maximum_observation_age_seconds > 0),
    default_validity_seconds BIGINT CHECK (default_validity_seconds IS NULL OR default_validity_seconds > 0),
    future_skew_tolerance_seconds BIGINT NOT NULL CHECK (future_skew_tolerance_seconds >= 0),
    minimum_passing_quantity NUMERIC,
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    changed_by_kind VARCHAR(16) NOT NULL CHECK (changed_by_kind IN ('HUMAN', 'SERVICE')),
    changed_by_issuer TEXT NOT NULL CHECK (length(btrim(changed_by_issuer)) > 0),
    changed_by_identifier_type VARCHAR(16) NOT NULL,
    changed_by_identifier TEXT NOT NULL CHECK (length(btrim(changed_by_identifier)) > 0),
    changed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (dimension_code, version),
    FOREIGN KEY (dimension_code, dimension_version)
        REFERENCES capacity.capacity_dimension_version (dimension_code, version)
        ON DELETE RESTRICT,
    CONSTRAINT capacity_policy_actor_type CHECK (
        (changed_by_kind = 'HUMAN' AND changed_by_identifier_type = 'subject') OR
        (changed_by_kind = 'SERVICE' AND changed_by_identifier_type = 'client_id')
    ),
    CONSTRAINT capacity_policy_version_unique UNIQUE (dimension_code, version, revision_id)
);

ALTER TABLE capacity.capacity_policy
    ADD CONSTRAINT capacity_policy_current_version_fk
        FOREIGN KEY (dimension_code, current_version)
        REFERENCES capacity.capacity_policy_version (dimension_code, version)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE capacity.capacity_policy_source (
    dimension_code VARCHAR(64) NOT NULL,
    policy_version BIGINT NOT NULL,
    source_code VARCHAR(128) NOT NULL CHECK (source_code ~ '^[A-Za-z][A-Za-z0-9._:-]{0,127}$'),
    priority INTEGER NOT NULL CHECK (priority >= 0),
    fallback_when_higher_priority_absent BOOLEAN NOT NULL,
    fallback_when_higher_priority_stale BOOLEAN NOT NULL,
    PRIMARY KEY (dimension_code, policy_version, source_code),
    FOREIGN KEY (dimension_code, policy_version)
        REFERENCES capacity.capacity_policy_version (dimension_code, version)
        ON DELETE RESTRICT
);

CREATE INDEX capacity_policy_source_priority_idx
    ON capacity.capacity_policy_source (dimension_code, policy_version, priority, source_code);

CREATE TABLE capacity.capacity_policy_readiness_state (
    dimension_code VARCHAR(64) NOT NULL,
    policy_version BIGINT NOT NULL,
    state_code VARCHAR(64) NOT NULL CHECK (state_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    PRIMARY KEY (dimension_code, policy_version, state_code),
    FOREIGN KEY (dimension_code, policy_version)
        REFERENCES capacity.capacity_policy_version (dimension_code, version)
        ON DELETE RESTRICT
);

CREATE TABLE capacity.command_receipt (
    scope_digest VARCHAR(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest VARCHAR(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint VARCHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state VARCHAR(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INTEGER,
    result_reference UUID,
    result_version BIGINT,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT capacity_receipt_completion_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND response_status IS NULL AND result_reference IS NULL AND result_version IS NULL)
        OR (receipt_state = 'COMPLETED' AND response_status BETWEEN 200 AND 299
            AND result_reference IS NOT NULL AND result_version > 0)
    )
);

CREATE INDEX capacity_dimension_version_history_idx
    ON capacity.capacity_dimension_version (dimension_code, version DESC);

CREATE INDEX capacity_policy_version_history_idx
    ON capacity.capacity_policy_version (dimension_code, version DESC);
