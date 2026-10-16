CREATE TABLE transport.destination_change_evidence (
    evidence_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL
        REFERENCES transport.destination_change_operation (operation_id) ON DELETE RESTRICT,
    reported_outcome varchar(16) NOT NULL CHECK (reported_outcome IN ('ACCEPTED', 'REJECTED')),
    source_namespace varchar(256) NOT NULL
        CHECK (source_namespace ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}$'),
    source_observation_id varchar(128) NOT NULL
        CHECK (source_observation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    source_occurred_at timestamptz NOT NULL,
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind IN ('HUMAN', 'SERVICE')),
    recorded_by_issuer varchar(256) NOT NULL,
    recorded_by_identifier_type varchar(16) NOT NULL,
    recorded_by_identifier varchar(512) NOT NULL,
    recorded_at timestamptz NOT NULL,
    disposition varchar(32) NOT NULL CHECK (disposition IN ('RESOLVED', 'RECONCILIATION_REQUIRED')),
    reason_code varchar(64) CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    correlation_id varchar(64) NOT NULL,
    CONSTRAINT transport_destination_change_evidence_actor_type CHECK (
        (recorded_by_kind = 'HUMAN' AND recorded_by_identifier_type = 'subject') OR
        (recorded_by_kind = 'SERVICE' AND recorded_by_identifier_type = 'client_id')
    ),
    CONSTRAINT transport_destination_change_evidence_reason_shape CHECK (
        (disposition = 'RECONCILIATION_REQUIRED' AND reason_code IS NOT NULL) OR
        (disposition = 'RESOLVED' AND reason_code IS NULL)
    ),
    CONSTRAINT transport_destination_change_evidence_source_identity UNIQUE (
        operation_id, source_namespace, source_observation_id
    )
);

CREATE INDEX transport_destination_change_evidence_history_idx
    ON transport.destination_change_evidence (operation_id, recorded_at, evidence_id);

COMMENT ON TABLE transport.destination_change_evidence IS
    'Append-only minimized provider/manual acknowledgement evidence; retained separately from the current operation disposition for reconciliation.';
