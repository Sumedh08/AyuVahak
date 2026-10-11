CREATE TABLE referral.consumer_event_receipt (
    event_id uuid PRIMARY KEY,
    event_type varchar(96) NOT NULL CHECK (
        event_type IN ('transfer.completed.v1', 'transfer.unfulfilled.v1')
    ),
    event_state varchar(16) NOT NULL CHECK (event_state IN ('PROCESSING', 'COMPLETED')),
    referral_id uuid REFERENCES referral.draft (referral_id) ON DELETE RESTRICT,
    outcome varchar(48) CHECK (outcome IS NULL OR outcome ~ '^[A-Z][A-Z0-9_]{1,47}$'),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT referral_consumer_event_receipt_result_shape CHECK (
        (event_state = 'PROCESSING' AND outcome IS NULL) OR
        (event_state = 'COMPLETED' AND outcome IS NOT NULL)
    )
);
