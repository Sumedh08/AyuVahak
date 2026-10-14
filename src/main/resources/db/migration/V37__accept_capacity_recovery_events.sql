ALTER TABLE transfer.consumer_event_receipt
    DROP CONSTRAINT consumer_event_receipt_event_type_check,
    ADD CONSTRAINT transfer_consumer_event_receipt_event_type_check CHECK (
        event_type IN (
            'referral.cancelled.v1',
            'capacity.reservation-expired.v1',
            'capacity.reservation-at-risk.v1',
            'capacity.reservation-recovered.v1'
        )
    );
