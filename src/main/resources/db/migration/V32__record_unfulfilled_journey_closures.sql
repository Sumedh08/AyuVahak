CREATE TABLE transfer.journey_closure (
    journey_id uuid PRIMARY KEY REFERENCES transfer.journey (journey_id),
    referral_id uuid NOT NULL,
    outcome text NOT NULL CHECK (outcome = 'UNFULFILLED'),
    reason_code text NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    closed_by_kind text NOT NULL CHECK (closed_by_kind IN ('HUMAN', 'SERVICE')),
    closed_by_issuer text NOT NULL,
    closed_by_identifier_type text NOT NULL,
    closed_by_identifier text NOT NULL,
    authority_code text NOT NULL CHECK (authority_code = 'TransferJourneyClosureAuthority'),
    organization_id uuid NOT NULL,
    facility_id uuid,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    aggregate_sequence bigint NOT NULL CHECK (aggregate_sequence > 0),
    CONSTRAINT transfer_journey_closure_sequence_unique
        UNIQUE (journey_id, aggregate_sequence)
);
