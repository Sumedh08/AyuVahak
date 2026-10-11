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
