ALTER TABLE transfer.capacity_reservation_request
    ADD COLUMN last_capacity_event_sequence bigint NOT NULL DEFAULT 0
        CHECK (last_capacity_event_sequence >= 0);

CREATE TABLE transfer.consumer_event_receipt (
    event_id uuid PRIMARY KEY,
    event_type varchar(96) NOT NULL CHECK (
        event_type IN (
            'referral.cancelled.v1',
            'capacity.reservation-expired.v1',
            'capacity.reservation-at-risk.v1'
        )
    ),
    event_state varchar(16) NOT NULL CHECK (event_state IN ('PROCESSING', 'COMPLETED')),
    transfer_id uuid REFERENCES transfer.transfer_record (transfer_id) ON DELETE RESTRICT,
    outcome varchar(48) CHECK (outcome IS NULL OR outcome ~ '^[A-Z][A-Z0-9_]{1,47}$'),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT transfer_consumer_event_receipt_result_shape CHECK (
        (event_state = 'PROCESSING' AND outcome IS NULL) OR
        (event_state = 'COMPLETED' AND outcome IS NOT NULL)
    )
);
