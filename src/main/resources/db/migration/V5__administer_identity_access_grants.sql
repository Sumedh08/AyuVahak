ALTER TABLE audit.audit_record
    DROP CONSTRAINT audit_record_authentication_evidence_check;

ALTER TABLE audit.audit_record
    ADD CONSTRAINT audit_authentication_evidence_valid CHECK (
        authentication_evidence IN (
            'NONE', 'INVALID_CREDENTIAL', 'TRUST_SOURCE_UNAVAILABLE',
            'HUMAN_ACCESS_TOKEN', 'SERVICE_ACCESS_TOKEN', 'CONFIGURED_SERVICE_IDENTITY',
            'LOCAL_OPERATOR_CONTROL'
        )
    );

ALTER TABLE identity_access.authorization_grant
    ADD COLUMN aggregate_sequence BIGINT NOT NULL DEFAULT 0
        CHECK (aggregate_sequence >= 0);

CREATE TABLE identity_access.bootstrap_marker (
    marker_id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (marker_id = 1),
    grant_id UUID NOT NULL REFERENCES identity_access.authorization_grant (grant_id),
    operator_issuer TEXT NOT NULL CHECK (length(operator_issuer) > 0),
    operator_reference TEXT NOT NULL CHECK (length(operator_reference) > 0),
    host_principal TEXT NOT NULL CHECK (length(host_principal) > 0),
    database_principal TEXT NOT NULL CHECK (length(database_principal) > 0),
    completed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE identity_access.grant_command_receipt (
    scope_digest CHAR(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest CHAR(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state VARCHAR(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status SMALLINT,
    result_reference UUID,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT grant_command_receipt_pk
        PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT grant_command_receipt_completion_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND response_status IS NULL AND result_reference IS NULL)
        OR
        (receipt_state = 'COMPLETED' AND response_status IS NOT NULL
            AND response_status BETWEEN 200 AND 299
            AND result_reference IS NOT NULL)
    )
);
