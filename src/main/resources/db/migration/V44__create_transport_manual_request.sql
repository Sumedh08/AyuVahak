CREATE SCHEMA transport;

CREATE TABLE transport.transport_request (
    transport_request_id uuid PRIMARY KEY,
    transfer_id uuid NOT NULL,
    origin_facility_id uuid NOT NULL,
    destination_facility_id uuid NOT NULL,
    initiated_by_kind varchar(16) NOT NULL CHECK (initiated_by_kind = 'HUMAN'),
    initiated_by_issuer varchar(256) NOT NULL CHECK (length(btrim(initiated_by_issuer)) > 0),
    initiated_by_identifier_type varchar(16) NOT NULL CHECK (initiated_by_identifier_type = 'subject'),
    initiated_by_identifier varchar(512) NOT NULL CHECK (
        length(btrim(initiated_by_identifier)) > 0
        AND initiated_by_identifier = btrim(initiated_by_identifier)
        AND initiated_by_identifier !~ '[[:cntrl:]]'
    ),
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind = 'SERVICE'),
    recorded_by_issuer varchar(256) NOT NULL CHECK (length(btrim(recorded_by_issuer)) > 0),
    recorded_by_identifier_type varchar(16) NOT NULL CHECK (recorded_by_identifier_type = 'client_id'),
    recorded_by_identifier varchar(512) NOT NULL CHECK (
        length(btrim(recorded_by_identifier)) > 0
        AND recorded_by_identifier = btrim(recorded_by_identifier)
        AND recorded_by_identifier !~ '[[:cntrl:]]'
    ),
    request_status varchar(32) NOT NULL CHECK (request_status IN (
        'REQUESTED', 'AWAITING_ASSIGNMENT', 'ASSIGNED', 'EN_ROUTE_TO_ORIGIN', 'AT_ORIGIN',
        'PATIENT_ONBOARD', 'EN_ROUTE_TO_DESTINATION', 'ARRIVED_DESTINATION', 'COMPLETED',
        'CANCELLED', 'INTERRUPTED', 'UNFULFILLED'
    )),
    requested_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    CONSTRAINT transport_request_origin_destination_differ CHECK (origin_facility_id <> destination_facility_id)
);

CREATE UNIQUE INDEX transport_one_active_request_per_transfer
    ON transport.transport_request (transfer_id)
    WHERE request_status NOT IN ('COMPLETED', 'CANCELLED', 'UNFULFILLED');

CREATE INDEX transport_request_transfer_history
    ON transport.transport_request (transfer_id, requested_at DESC, transport_request_id);

CREATE TABLE transport.command_receipt (
    scope_digest char(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest char(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state varchar(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status integer,
    result_reference uuid,
    result_version bigint,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT transport_receipt_result_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND response_status IS NULL AND result_reference IS NULL AND result_version IS NULL)
        OR
        (receipt_state = 'COMPLETED' AND response_status = 201 AND result_reference IS NOT NULL AND result_version > 0)
    ),
    CONSTRAINT transport_receipt_request_fk FOREIGN KEY (result_reference)
        REFERENCES transport.transport_request (transport_request_id) ON DELETE RESTRICT
);
