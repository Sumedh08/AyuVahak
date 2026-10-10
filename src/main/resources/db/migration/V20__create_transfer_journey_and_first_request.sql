CREATE SCHEMA transfer;

CREATE TABLE transfer.journey (
    journey_id uuid PRIMARY KEY,
    referral_id uuid NOT NULL UNIQUE,
    origin_organization_id uuid NOT NULL,
    origin_facility_id uuid,
    created_by_kind varchar(16) NOT NULL CHECK (created_by_kind IN ('HUMAN', 'SERVICE')),
    created_by_issuer varchar(256) NOT NULL CHECK (length(btrim(created_by_issuer)) > 0),
    created_by_identifier_type varchar(16) NOT NULL,
    created_by_identifier varchar(512) NOT NULL CHECK (
        length(btrim(created_by_identifier)) > 0
        AND created_by_identifier = btrim(created_by_identifier)
        AND created_by_identifier !~ '[[:cntrl:]]'
    ),
    created_at timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    CONSTRAINT transfer_journey_creator_type CHECK (
        (created_by_kind = 'HUMAN' AND created_by_identifier_type = 'subject') OR
        (created_by_kind = 'SERVICE' AND created_by_identifier_type = 'client_id')
    )
);

CREATE TABLE transfer.request_attempt (
    request_attempt_id uuid PRIMARY KEY,
    journey_id uuid NOT NULL REFERENCES transfer.journey (journey_id) ON DELETE RESTRICT,
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    referral_id uuid NOT NULL,
    referral_revision bigint NOT NULL CHECK (referral_revision > 0),
    recommendation_id uuid NOT NULL,
    destination_facility_id uuid NOT NULL,
    request_status varchar(16) NOT NULL CHECK (request_status = 'PENDING'),
    requested_by_kind varchar(16) NOT NULL CHECK (requested_by_kind IN ('HUMAN', 'SERVICE')),
    requested_by_issuer varchar(256) NOT NULL CHECK (length(btrim(requested_by_issuer)) > 0),
    requested_by_identifier_type varchar(16) NOT NULL,
    requested_by_identifier varchar(512) NOT NULL CHECK (
        length(btrim(requested_by_identifier)) > 0
        AND requested_by_identifier = btrim(requested_by_identifier)
        AND requested_by_identifier !~ '[[:cntrl:]]'
    ),
    requested_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > requested_at),
    version bigint NOT NULL CHECK (version > 0),
    CONSTRAINT transfer_request_attempt_number_unique UNIQUE (journey_id, attempt_number),
    CONSTRAINT transfer_first_attempt_number CHECK (attempt_number = 1),
    CONSTRAINT transfer_request_requester_type CHECK (
        (requested_by_kind = 'HUMAN' AND requested_by_identifier_type = 'subject') OR
        (requested_by_kind = 'SERVICE' AND requested_by_identifier_type = 'client_id')
    )
);

CREATE TABLE transfer.command_receipt (
    scope_digest char(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest char(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state varchar(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status integer,
    result_reference uuid,
    result_version bigint,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT transfer_receipt_result_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND response_status IS NULL
            AND result_reference IS NULL AND result_version IS NULL)
        OR
        (receipt_state = 'COMPLETED' AND response_status BETWEEN 200 AND 299
            AND result_reference IS NOT NULL AND result_version > 0)
    ),
    CONSTRAINT transfer_receipt_journey_fk FOREIGN KEY (result_reference)
        REFERENCES transfer.journey (journey_id) ON DELETE RESTRICT
);
