CREATE TABLE capacity.reservation_operation (
    operation_id uuid PRIMARY KEY,
    transfer_id uuid NOT NULL UNIQUE,
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    outcome varchar(16) NOT NULL CHECK (
        outcome IN ('PENDING', 'RESERVED', 'CONFLICT', 'UNKNOWN', 'INVALID')
    ),
    reservation_id uuid,
    reason_code varchar(80) CHECK (
        reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'
    ),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT capacity_reservation_operation_result_shape CHECK (
        (outcome = 'RESERVED' AND reservation_id IS NOT NULL AND reason_code IS NULL) OR
        (outcome <> 'RESERVED' AND reservation_id IS NULL)
    )
);

CREATE TABLE capacity.coordination_reservation (
    reservation_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE
        REFERENCES capacity.reservation_operation (operation_id) ON DELETE RESTRICT,
    transfer_id uuid NOT NULL UNIQUE,
    facility_issuer text NOT NULL CHECK (facility_issuer = 'urn:ayuvahak:directory'),
    facility_identifier_type varchar(64) NOT NULL CHECK (facility_identifier_type = 'Facility'),
    facility_identifier uuid NOT NULL,
    dimension_code varchar(64) NOT NULL,
    dimension_version bigint NOT NULL,
    policy_version bigint NOT NULL,
    unit_code varchar(32) NOT NULL,
    quantity numeric NOT NULL CHECK (quantity > 0),
    interval_start timestamptz NOT NULL,
    interval_end timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    basis_observation_ids jsonb NOT NULL CHECK (
        jsonb_typeof(basis_observation_ids) = 'array'
        AND jsonb_array_length(basis_observation_ids) > 0
    ),
    created_at timestamptz NOT NULL,
    CONSTRAINT capacity_coordination_reservation_interval CHECK (
        interval_end > interval_start AND expires_at = interval_end
    ),
    CONSTRAINT capacity_coordination_reservation_dimension_fk
        FOREIGN KEY (dimension_code, dimension_version)
        REFERENCES capacity.capacity_dimension_version (dimension_code, version)
        ON DELETE RESTRICT,
    CONSTRAINT capacity_coordination_reservation_policy_fk
        FOREIGN KEY (dimension_code, policy_version)
        REFERENCES capacity.capacity_policy_version (dimension_code, version)
        ON DELETE RESTRICT
);

ALTER TABLE capacity.reservation_operation
    ADD CONSTRAINT capacity_reservation_operation_reservation_fk
        FOREIGN KEY (reservation_id)
        REFERENCES capacity.coordination_reservation (reservation_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX capacity_coordination_reservation_active_idx
    ON capacity.coordination_reservation
        (facility_issuer, facility_identifier, dimension_code, unit_code, expires_at);

CREATE TABLE capacity.reservation_command_receipt (
    scope_digest char(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest char(64) NOT NULL
        CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    operation_id uuid NOT NULL,
    receipt_state varchar(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    outcome varchar(16) CHECK (outcome IS NULL OR outcome IN ('RESERVED', 'CONFLICT', 'UNKNOWN', 'INVALID')),
    reservation_id uuid,
    reason_code varchar(80) CHECK (
        reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'
    ),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT capacity_reservation_receipt_result_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND outcome IS NULL AND reservation_id IS NULL AND reason_code IS NULL)
        OR
        (receipt_state = 'COMPLETED' AND outcome = 'RESERVED' AND reservation_id IS NOT NULL AND reason_code IS NULL)
        OR
        (receipt_state = 'COMPLETED' AND outcome IN ('CONFLICT', 'UNKNOWN', 'INVALID')
            AND reservation_id IS NULL)
    )
);

ALTER TABLE capacity.reservation_command_receipt
    ADD CONSTRAINT capacity_reservation_receipt_reservation_fk
        FOREIGN KEY (reservation_id)
        REFERENCES capacity.coordination_reservation (reservation_id)
        DEFERRABLE INITIALLY DEFERRED;
