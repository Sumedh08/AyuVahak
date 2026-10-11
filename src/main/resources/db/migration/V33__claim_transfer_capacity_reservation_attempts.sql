ALTER TABLE transfer.capacity_reservation_request
    DROP CONSTRAINT capacity_reservation_request_outcome_check,
    ADD CONSTRAINT capacity_reservation_request_outcome_check CHECK (
        outcome IN (
            'PENDING', 'IN_PROGRESS', 'RESERVED', 'CONFLICT', 'UNKNOWN', 'UNAVAILABLE', 'INVALID',
            'UNCONFIGURED', 'AT_RISK', 'RELEASED', 'EXPIRED', 'NOT_RESERVED'
        )
    );
