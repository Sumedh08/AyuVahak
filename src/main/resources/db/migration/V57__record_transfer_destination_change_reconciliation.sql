CREATE TABLE transfer.destination_change_reconciliation (
    operation_id uuid PRIMARY KEY
        REFERENCES transfer.destination_change_operation (operation_id) ON DELETE RESTRICT,
    resolution_code varchar(40) NOT NULL CHECK (resolution_code = 'RETAIN_OLD_DESTINATION'),
    evidence_reference varchar(128) NOT NULL
        CHECK (evidence_reference ~ '^[A-Za-z0-9:/._-]{1,128}$'),
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind = 'HUMAN'),
    recorded_by_issuer varchar(256) NOT NULL,
    recorded_by_identifier_type varchar(16) NOT NULL CHECK (recorded_by_identifier_type = 'subject'),
    recorded_by_identifier varchar(512) NOT NULL,
    organization_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    correlation_id varchar(64) NOT NULL,
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[a-f0-9]{64}$')
);

COMMENT ON TABLE transfer.destination_change_reconciliation IS
    'Append-only, attributed operator evidence that a failed post-movement destination change retains the old active destination.';
