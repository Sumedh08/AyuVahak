ALTER TABLE transfer.transfer_record
    DROP CONSTRAINT transfer_record_transfer_status_check,
    ADD CONSTRAINT transfer_record_transfer_status_check
        CHECK (transfer_status IN ('AWAITING_RESERVATION', 'READY')),
    DROP CONSTRAINT transfer_record_version_check,
    ADD CONSTRAINT transfer_record_version_check CHECK (version > 0);

CREATE TABLE transfer.capacity_reservation_request (
    operation_id uuid PRIMARY KEY,
    transfer_id uuid NOT NULL UNIQUE
        REFERENCES transfer.transfer_record (transfer_id) ON DELETE RESTRICT,
    organization_issuer text NOT NULL CHECK (organization_issuer = 'urn:ayuvahak:directory'),
    organization_identifier_type varchar(64) NOT NULL CHECK (organization_identifier_type = 'Organization'),
    organization_identifier uuid NOT NULL,
    facility_issuer text NOT NULL CHECK (facility_issuer = 'urn:ayuvahak:directory'),
    facility_identifier_type varchar(64) NOT NULL CHECK (facility_identifier_type = 'Facility'),
    facility_identifier uuid NOT NULL,
    dimension_code varchar(64),
    unit_code varchar(32),
    quantity numeric CHECK (quantity IS NULL OR quantity > 0),
    interval_start timestamptz,
    interval_end timestamptz,
    expires_at timestamptz,
    outcome varchar(16) NOT NULL CHECK (
        outcome IN ('PENDING', 'RESERVED', 'CONFLICT', 'UNKNOWN', 'UNAVAILABLE', 'INVALID', 'UNCONFIGURED')
    ),
    reservation_id uuid,
    reason_code varchar(80) CHECK (
        reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'
    ),
    attempt_count integer NOT NULL CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT transfer_capacity_reservation_operation_unique UNIQUE (operation_id, transfer_id),
    CONSTRAINT transfer_capacity_reservation_operation_is_transfer
        CHECK (operation_id = transfer_id),
    CONSTRAINT transfer_capacity_reservation_configuration_shape CHECK (
        (
            outcome = 'UNCONFIGURED'
            AND dimension_code IS NULL AND unit_code IS NULL AND quantity IS NULL
            AND interval_start IS NULL AND interval_end IS NULL AND expires_at IS NULL
        )
        OR
        (
            outcome <> 'UNCONFIGURED'
            AND dimension_code IS NOT NULL AND unit_code IS NOT NULL AND quantity IS NOT NULL
            AND interval_start IS NOT NULL AND interval_end IS NOT NULL AND expires_at IS NOT NULL
            AND interval_end > interval_start AND expires_at = interval_end
        )
    ),
    CONSTRAINT transfer_capacity_reservation_result_shape CHECK (
        (outcome = 'RESERVED' AND reservation_id IS NOT NULL AND reason_code IS NULL)
        OR (outcome <> 'RESERVED' AND reservation_id IS NULL)
    )
);

CREATE INDEX transfer_capacity_reservation_recovery_idx
    ON transfer.capacity_reservation_request (outcome, next_attempt_at, transfer_id);
