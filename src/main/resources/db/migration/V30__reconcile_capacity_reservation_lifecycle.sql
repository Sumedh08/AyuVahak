ALTER TABLE transfer.capacity_reservation_request
    DROP CONSTRAINT capacity_reservation_request_outcome_check,
    ADD CONSTRAINT capacity_reservation_request_outcome_check CHECK (
        outcome IN (
            'PENDING', 'RESERVED', 'CONFLICT', 'UNKNOWN', 'UNAVAILABLE', 'INVALID',
            'UNCONFIGURED', 'AT_RISK', 'RELEASED', 'EXPIRED', 'NOT_RESERVED'
        )
    ),
    DROP CONSTRAINT transfer_capacity_reservation_result_shape,
    ADD CONSTRAINT transfer_capacity_reservation_result_shape CHECK (
        (outcome IN ('RESERVED', 'AT_RISK', 'RELEASED', 'EXPIRED') AND reservation_id IS NOT NULL) OR
        (outcome NOT IN ('RESERVED', 'AT_RISK', 'RELEASED', 'EXPIRED') AND reservation_id IS NULL)
    );

ALTER TABLE capacity.coordination_reservation
    ADD COLUMN lifecycle_checked_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX capacity_coordination_reservation_lifecycle_due_idx
    ON capacity.coordination_reservation (lifecycle_checked_at, expires_at, reservation_id)
    WHERE reservation_status IN ('ACTIVE', 'AT_RISK');
