CREATE TABLE transport.status_observation (
    status_observation_id uuid PRIMARY KEY,
    transport_request_id uuid NOT NULL REFERENCES transport.transport_request(transport_request_id) ON DELETE RESTRICT,
    source_namespace varchar(256) NOT NULL CHECK (length(btrim(source_namespace)) > 0 AND source_namespace = btrim(source_namespace)),
    source_observation_id varchar(128) NOT NULL CHECK (length(btrim(source_observation_id)) > 0 AND source_observation_id = btrim(source_observation_id)),
    source_sequence bigint CHECK (source_sequence IS NULL OR source_sequence >= 0),
    status_code varchar(32) NOT NULL CHECK (status_code IN (
        'AWAITING_ASSIGNMENT', 'EN_ROUTE_TO_ORIGIN', 'AT_ORIGIN', 'PATIENT_ONBOARD',
        'EN_ROUTE_TO_DESTINATION', 'ARRIVED_DESTINATION', 'INTERRUPTED'
    )),
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind IN ('HUMAN', 'SERVICE')),
    recorded_by_issuer varchar(256) NOT NULL CHECK (length(btrim(recorded_by_issuer)) > 0),
    recorded_by_identifier_type varchar(16) NOT NULL CHECK (recorded_by_identifier_type IN ('subject', 'client_id')),
    recorded_by_identifier varchar(512) NOT NULL CHECK (length(btrim(recorded_by_identifier)) > 0 AND recorded_by_identifier = btrim(recorded_by_identifier)),
    authority_code varchar(64) NOT NULL CHECK (authority_code IN ('TransportStatusAuthority', 'TransportDispatchCoordinationAuthority')),
    organization_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    correlation_id varchar(128) NOT NULL,
    observation_outcome varchar(32) NOT NULL CHECK (observation_outcome IN ('APPLIED', 'RECONCILIATION_REQUIRED', 'FUTURE_SKEWED')),
    reason_code varchar(80) CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    request_status_after varchar(32) NOT NULL CHECK (request_status_after IN (
        'REQUESTED', 'AWAITING_ASSIGNMENT', 'ASSIGNED', 'EN_ROUTE_TO_ORIGIN', 'AT_ORIGIN',
        'PATIENT_ONBOARD', 'EN_ROUTE_TO_DESTINATION', 'ARRIVED_DESTINATION', 'COMPLETED',
        'CANCELLED', 'INTERRUPTED', 'UNFULFILLED'
    )),
    resulting_request_version bigint NOT NULL CHECK (resulting_request_version > 0),
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT transport_status_source_identity UNIQUE (transport_request_id, source_namespace, source_observation_id)
);

CREATE INDEX transport_status_order
    ON transport.status_observation (transport_request_id, source_namespace, occurred_at DESC, source_sequence DESC);

CREATE TABLE transport.assignment_observation (
    assignment_observation_id uuid PRIMARY KEY,
    transport_request_id uuid NOT NULL REFERENCES transport.transport_request(transport_request_id) ON DELETE RESTRICT,
    source_namespace varchar(256) NOT NULL CHECK (length(btrim(source_namespace)) > 0 AND source_namespace = btrim(source_namespace)),
    source_observation_id varchar(128) NOT NULL CHECK (length(btrim(source_observation_id)) > 0 AND source_observation_id = btrim(source_observation_id)),
    assignment_reference varchar(256) NOT NULL CHECK (length(btrim(assignment_reference)) > 0 AND assignment_reference = btrim(assignment_reference)),
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind IN ('HUMAN', 'SERVICE')),
    recorded_by_issuer varchar(256) NOT NULL CHECK (length(btrim(recorded_by_issuer)) > 0),
    recorded_by_identifier_type varchar(16) NOT NULL CHECK (recorded_by_identifier_type IN ('subject', 'client_id')),
    recorded_by_identifier varchar(512) NOT NULL CHECK (length(btrim(recorded_by_identifier)) > 0 AND recorded_by_identifier = btrim(recorded_by_identifier)),
    authority_code varchar(64) NOT NULL CHECK (authority_code = 'TransportDispatchCoordinationAuthority'),
    organization_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    correlation_id varchar(128) NOT NULL,
    resulting_request_version bigint NOT NULL CHECK (resulting_request_version > 1),
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT transport_assignment_source_identity UNIQUE (transport_request_id, source_namespace, source_observation_id),
    CONSTRAINT transport_assignment_reference_identity UNIQUE (transport_request_id, source_namespace, assignment_reference)
);

CREATE TABLE transport.eta_observation (
    eta_observation_id uuid PRIMARY KEY,
    transport_request_id uuid NOT NULL REFERENCES transport.transport_request(transport_request_id) ON DELETE RESTRICT,
    source_namespace varchar(256) NOT NULL CHECK (length(btrim(source_namespace)) > 0 AND source_namespace = btrim(source_namespace)),
    source_observation_id varchar(128) NOT NULL CHECK (length(btrim(source_observation_id)) > 0 AND source_observation_id = btrim(source_observation_id)),
    estimated_arrival_at timestamptz NOT NULL,
    observed_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind IN ('HUMAN', 'SERVICE')),
    recorded_by_issuer varchar(256) NOT NULL CHECK (length(btrim(recorded_by_issuer)) > 0),
    recorded_by_identifier_type varchar(16) NOT NULL CHECK (recorded_by_identifier_type IN ('subject', 'client_id')),
    recorded_by_identifier varchar(512) NOT NULL CHECK (length(btrim(recorded_by_identifier)) > 0 AND recorded_by_identifier = btrim(recorded_by_identifier)),
    authority_code varchar(64) NOT NULL CHECK (authority_code = 'TransportStatusAuthority'),
    organization_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    correlation_id varchar(128) NOT NULL,
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT transport_eta_source_identity UNIQUE (transport_request_id, source_namespace, source_observation_id),
    CONSTRAINT transport_eta_times_valid CHECK (estimated_arrival_at > observed_at AND valid_until > recorded_at)
);

CREATE INDEX transport_eta_request_freshness
    ON transport.eta_observation (transport_request_id, valid_until DESC, recorded_at DESC);
