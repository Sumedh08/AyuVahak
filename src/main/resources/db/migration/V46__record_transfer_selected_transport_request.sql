CREATE TABLE transfer.transport_request_link (
    transport_request_id uuid PRIMARY KEY,
    transfer_id uuid NOT NULL REFERENCES transfer.transfer_record (transfer_id) ON DELETE RESTRICT,
    selected boolean NOT NULL,
    linked_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX transfer_one_selected_transport_request
    ON transfer.transport_request_link (transfer_id)
    WHERE selected;

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
            'transport.unfulfilled.v1'
        )
    );
