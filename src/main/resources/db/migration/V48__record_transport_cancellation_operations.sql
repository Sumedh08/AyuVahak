ALTER TABLE transport.assignment_observation
    ADD COLUMN observation_outcome varchar(32) NOT NULL DEFAULT 'APPLIED'
        CHECK (observation_outcome IN ('APPLIED', 'RECONCILIATION_REQUIRED')),
    ADD COLUMN request_status_after varchar(32) NOT NULL DEFAULT 'ASSIGNED'
        CHECK (request_status_after IN (
            'REQUESTED', 'AWAITING_ASSIGNMENT', 'ASSIGNED', 'EN_ROUTE_TO_ORIGIN', 'AT_ORIGIN',
            'PATIENT_ONBOARD', 'EN_ROUTE_TO_DESTINATION', 'ARRIVED_DESTINATION', 'COMPLETED',
            'CANCELLED', 'INTERRUPTED', 'UNFULFILLED'
        ));

CREATE TABLE transport.cancellation_operation (
    cancellation_operation_id uuid PRIMARY KEY,
    transport_request_id uuid NOT NULL REFERENCES transport.transport_request(transport_request_id) ON DELETE RESTRICT,
    reason_code varchar(64) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    operation_state varchar(32) NOT NULL CHECK (operation_state IN (
        'PENDING', 'OUTCOME_UNKNOWN', 'RECONCILIATION_REQUIRED', 'RESOLVED'
    )),
    outcome varchar(40) CHECK (outcome IS NULL OR outcome IN (
        'CANCELLED_BEFORE_MOVEMENT', 'CANCEL_REJECTED_ACTIVE', 'MOVEMENT_ALREADY_STARTED'
    )),
    initiated_at timestamptz NOT NULL,
    initiated_by_kind varchar(16) NOT NULL CHECK (initiated_by_kind IN ('HUMAN', 'SERVICE')),
    initiated_by_issuer varchar(256) NOT NULL CHECK (length(btrim(initiated_by_issuer)) > 0),
    initiated_by_identifier_type varchar(16) NOT NULL CHECK (initiated_by_identifier_type IN ('subject', 'client_id')),
    initiated_by_identifier varchar(512) NOT NULL CHECK (length(btrim(initiated_by_identifier)) > 0 AND initiated_by_identifier = btrim(initiated_by_identifier)),
    authority_code varchar(64) NOT NULL CHECK (authority_code = 'TransportCancellationAuthority'),
    organization_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    correlation_id varchar(128) NOT NULL,
    resolved_at timestamptz,
    source_namespace varchar(256),
    source_observation_id varchar(128),
    resolution_fingerprint char(64) CHECK (resolution_fingerprint IS NULL OR resolution_fingerprint ~ '^[0-9a-f]{64}$'),
    source_occurred_at timestamptz,
    resolution_correlation_id varchar(128),
    resolution_event_id uuid,
    version bigint NOT NULL CHECK (version > 0),
    CONSTRAINT transport_cancellation_resolution_shape CHECK (
        (operation_state = 'RESOLVED' AND outcome IS NOT NULL AND resolved_at IS NOT NULL
            AND source_namespace IS NOT NULL AND source_observation_id IS NOT NULL
            AND resolution_fingerprint IS NOT NULL AND source_occurred_at IS NOT NULL
            AND resolution_correlation_id IS NOT NULL AND resolution_event_id IS NOT NULL)
        OR
        (operation_state <> 'RESOLVED' AND outcome IS NULL AND resolved_at IS NULL
            AND source_namespace IS NULL AND source_observation_id IS NULL
            AND resolution_fingerprint IS NULL AND source_occurred_at IS NULL
            AND resolution_correlation_id IS NULL AND resolution_event_id IS NULL)
    )
);

CREATE UNIQUE INDEX transport_one_unresolved_cancellation_per_request
    ON transport.cancellation_operation (transport_request_id)
    WHERE operation_state IN ('PENDING', 'OUTCOME_UNKNOWN', 'RECONCILIATION_REQUIRED');

CREATE UNIQUE INDEX transport_cancellation_source_identity
    ON transport.cancellation_operation (cancellation_operation_id, source_namespace, source_observation_id)
    WHERE source_namespace IS NOT NULL;

CREATE TABLE transport.cancellation_command_receipt (
    scope_digest char(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest char(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state varchar(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    cancellation_operation_id uuid REFERENCES transport.cancellation_operation(cancellation_operation_id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT transport_cancellation_receipt_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND cancellation_operation_id IS NULL)
        OR (receipt_state = 'COMPLETED' AND cancellation_operation_id IS NOT NULL)
    )
);
