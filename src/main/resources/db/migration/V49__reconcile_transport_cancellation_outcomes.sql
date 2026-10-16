ALTER TABLE transfer.consumer_event_receipt
    DROP CONSTRAINT transfer_consumer_event_receipt_event_type_check,
    ADD CONSTRAINT transfer_consumer_event_receipt_event_type_check CHECK (
        event_type IN (
            'referral.cancelled.v1',
            'capacity.reservation-expired.v1',
            'capacity.reservation-at-risk.v1',
            'capacity.reservation-recovered.v1',
            'transport.departure-confirmed.v1',
            'transport.arrival-reported.v1',
            'transport.interrupted.v1',
            'transport.unfulfilled.v1',
            'transport.cancellation-resolved.v1'
        )
    );

CREATE TABLE transfer.transport_cancellation_outcome (
    cancellation_operation_id uuid PRIMARY KEY,
    transfer_id uuid NOT NULL REFERENCES transfer.transfer_record(transfer_id) ON DELETE RESTRICT,
    transport_request_id uuid NOT NULL,
    outcome varchar(40) NOT NULL CHECK (outcome IN (
        'CANCELLED_BEFORE_MOVEMENT', 'CANCEL_REJECTED_ACTIVE', 'MOVEMENT_ALREADY_STARTED'
    )),
    event_id uuid NOT NULL UNIQUE,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL
);

CREATE INDEX transfer_transport_cancellation_outcome_link
    ON transfer.transport_cancellation_outcome (transfer_id, transport_request_id, recorded_at DESC);
