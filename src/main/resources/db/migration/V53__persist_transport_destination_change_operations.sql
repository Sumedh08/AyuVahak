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
            'transport.cancellation-resolved.v1',
            'transport.destination-change-resolved.v1'
        )
    );

CREATE TABLE transport.destination_change_operation (
    operation_id uuid PRIMARY KEY,
    transfer_destination_change_id uuid NOT NULL UNIQUE,
    transfer_id uuid NOT NULL,
    transport_request_id uuid NOT NULL
        REFERENCES transport.transport_request (transport_request_id) ON DELETE RESTRICT,
    old_facility_id uuid NOT NULL,
    proposed_facility_id uuid NOT NULL,
    reason_code varchar(64) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    operation_state varchar(32) NOT NULL CHECK (operation_state IN (
        'PENDING', 'OUTCOME_UNKNOWN', 'RECONCILIATION_REQUIRED', 'RESOLVED'
    )),
    outcome varchar(16) CHECK (outcome IS NULL OR outcome IN ('ACCEPTED', 'REJECTED')),
    initiated_by_kind varchar(16) NOT NULL CHECK (initiated_by_kind IN ('HUMAN', 'SERVICE')),
    initiated_by_issuer varchar(256) NOT NULL,
    initiated_by_identifier_type varchar(16) NOT NULL,
    initiated_by_identifier varchar(512) NOT NULL,
    recorded_by_kind varchar(16),
    recorded_by_issuer varchar(256),
    recorded_by_identifier_type varchar(16),
    recorded_by_identifier varchar(512),
    source_namespace varchar(256),
    source_observation_id varchar(256),
    resolution_fingerprint char(64) CHECK (
        resolution_fingerprint IS NULL OR resolution_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    source_occurred_at timestamptz,
    resolved_at timestamptz,
    resolution_event_id uuid UNIQUE,
    correlation_id varchar(64) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT transport_destination_change_actor_type CHECK (
        (initiated_by_kind = 'HUMAN' AND initiated_by_identifier_type = 'subject') OR
        (initiated_by_kind = 'SERVICE' AND initiated_by_identifier_type = 'client_id')
    ),
    CONSTRAINT transport_destination_change_resolution_shape CHECK (
        (operation_state = 'RESOLVED' AND outcome IS NOT NULL
            AND recorded_by_kind IS NOT NULL AND recorded_by_issuer IS NOT NULL
            AND recorded_by_identifier_type IS NOT NULL AND recorded_by_identifier IS NOT NULL
            AND source_namespace IS NOT NULL AND source_observation_id IS NOT NULL
            AND resolution_fingerprint IS NOT NULL AND source_occurred_at IS NOT NULL
            AND resolved_at IS NOT NULL AND resolution_event_id IS NOT NULL)
        OR
        (operation_state <> 'RESOLVED' AND outcome IS NULL AND resolution_event_id IS NULL)
    )
);

CREATE INDEX transport_destination_change_recovery_idx
    ON transport.destination_change_operation (operation_state, updated_at, operation_id);
