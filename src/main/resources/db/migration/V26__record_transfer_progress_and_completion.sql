ALTER TABLE transfer.transfer_record
    DROP CONSTRAINT transfer_record_transfer_status_check,
    ADD CONSTRAINT transfer_record_transfer_status_check
        CHECK (transfer_status IN (
            'AWAITING_RESERVATION', 'READY', 'IN_TRANSIT', 'INTERRUPTED',
            'ARRIVED', 'HANDED_OVER', 'COMPLETED'
        ));

CREATE TABLE transfer.progress_fact (
    progress_fact_id uuid PRIMARY KEY,
    transfer_id uuid NOT NULL REFERENCES transfer.transfer_record (transfer_id) ON DELETE RESTRICT,
    journey_id uuid NOT NULL REFERENCES transfer.journey (journey_id) ON DELETE RESTRICT,
    fact_kind varchar(24) NOT NULL CHECK (
        fact_kind IN ('DEPARTURE', 'INTERRUPTION', 'RESUMPTION', 'ARRIVAL', 'HANDOVER', 'COMPLETION')
    ),
    previous_status varchar(32) CHECK (
        previous_status IS NULL OR previous_status IN (
            'AWAITING_RESERVATION', 'READY', 'IN_TRANSIT', 'INTERRUPTED', 'ARRIVED', 'HANDED_OVER'
        )
    ),
    transfer_status varchar(32) NOT NULL CHECK (
        transfer_status IN ('IN_TRANSIT', 'INTERRUPTED', 'ARRIVED', 'HANDED_OVER', 'COMPLETED')
    ),
    exception_code varchar(80) CHECK (
        exception_code IS NULL OR exception_code ~ '^[A-Z][A-Z0-9_]{1,79}$'
    ),
    reason_code varchar(64) CHECK (
        reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'
    ),
    source_reference varchar(128) CHECK (
        source_reference IS NULL OR source_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
    ),
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind IN ('HUMAN', 'SERVICE')),
    recorded_by_issuer varchar(256) NOT NULL CHECK (length(btrim(recorded_by_issuer)) > 0),
    recorded_by_identifier_type varchar(16) NOT NULL,
    recorded_by_identifier varchar(512) NOT NULL CHECK (
        length(btrim(recorded_by_identifier)) > 0
        AND recorded_by_identifier = btrim(recorded_by_identifier)
        AND recorded_by_identifier !~ '[[:cntrl:]]'
    ),
    authority_code varchar(64) NOT NULL CHECK (
        authority_code IN (
            'TransferProgressAuthority', 'TransferHandoverAuthority', 'TransferCompletionAuthority'
        )
    ),
    organization_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    transfer_version bigint NOT NULL CHECK (transfer_version > 1),
    aggregate_sequence bigint NOT NULL CHECK (aggregate_sequence > 1),
    CONSTRAINT transfer_progress_actor_type CHECK (
        (recorded_by_kind = 'HUMAN' AND recorded_by_identifier_type = 'subject') OR
        (recorded_by_kind = 'SERVICE' AND recorded_by_identifier_type = 'client_id')
    ),
    CONSTRAINT transfer_progress_kind_status CHECK (
        (fact_kind = 'DEPARTURE' AND previous_status = 'READY' AND transfer_status = 'IN_TRANSIT'
            AND authority_code = 'TransferProgressAuthority') OR
        (fact_kind = 'INTERRUPTION' AND previous_status = 'IN_TRANSIT' AND transfer_status = 'INTERRUPTED'
            AND authority_code = 'TransferProgressAuthority' AND reason_code IS NOT NULL) OR
        (fact_kind = 'RESUMPTION' AND previous_status = 'INTERRUPTED' AND transfer_status = 'IN_TRANSIT'
            AND authority_code = 'TransferProgressAuthority') OR
        (fact_kind = 'ARRIVAL' AND previous_status IN (
            'AWAITING_RESERVATION', 'READY', 'IN_TRANSIT', 'INTERRUPTED'
        ) AND transfer_status = 'ARRIVED' AND authority_code = 'TransferProgressAuthority') OR
        (fact_kind = 'HANDOVER' AND previous_status = 'ARRIVED' AND transfer_status = 'HANDED_OVER'
            AND authority_code = 'TransferHandoverAuthority' AND exception_code IS NULL) OR
        (fact_kind = 'COMPLETION' AND previous_status = 'HANDED_OVER' AND transfer_status = 'COMPLETED'
            AND authority_code = 'TransferCompletionAuthority' AND reason_code IS NOT NULL
            AND exception_code IS NULL)
    ),
    CONSTRAINT transfer_progress_exception_shape CHECK (
        (fact_kind = 'ARRIVAL' OR exception_code IS NULL)
    ),
    CONSTRAINT transfer_progress_version_unique UNIQUE (transfer_id, transfer_version),
    CONSTRAINT transfer_progress_aggregate_sequence_unique UNIQUE (journey_id, aggregate_sequence),
    CONSTRAINT transfer_progress_correction_target_unique
        UNIQUE (progress_fact_id, transfer_id, journey_id)
);

CREATE TABLE transfer.progress_correction (
    correction_id uuid PRIMARY KEY,
    transfer_id uuid NOT NULL REFERENCES transfer.transfer_record (transfer_id) ON DELETE RESTRICT,
    journey_id uuid NOT NULL REFERENCES transfer.journey (journey_id) ON DELETE RESTRICT,
    corrects_progress_fact_id uuid NOT NULL,
    correction_kind varchar(24) NOT NULL CHECK (
        correction_kind IN ('OCCURRENCE_TIME', 'REPORTED_RESULT')
    ),
    corrected_occurred_at timestamptz,
    corrected_result_code varchar(80) CHECK (
        corrected_result_code IS NULL OR corrected_result_code ~ '^[A-Z][A-Z0-9_]{1,79}$'
    ),
    reason_code varchar(64) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind IN ('HUMAN', 'SERVICE')),
    recorded_by_issuer varchar(256) NOT NULL CHECK (length(btrim(recorded_by_issuer)) > 0),
    recorded_by_identifier_type varchar(16) NOT NULL,
    recorded_by_identifier varchar(512) NOT NULL CHECK (
        length(btrim(recorded_by_identifier)) > 0
        AND recorded_by_identifier = btrim(recorded_by_identifier)
        AND recorded_by_identifier !~ '[[:cntrl:]]'
    ),
    authority_code varchar(64) NOT NULL CHECK (
        authority_code IN (
            'TransferProgressAuthority', 'TransferHandoverAuthority', 'TransferCompletionAuthority'
        )
    ),
    organization_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    transfer_version bigint NOT NULL CHECK (transfer_version > 1),
    aggregate_sequence bigint NOT NULL CHECK (aggregate_sequence > 1),
    CONSTRAINT transfer_correction_actor_type CHECK (
        (recorded_by_kind = 'HUMAN' AND recorded_by_identifier_type = 'subject') OR
        (recorded_by_kind = 'SERVICE' AND recorded_by_identifier_type = 'client_id')
    ),
    CONSTRAINT transfer_correction_target_same_transfer FOREIGN KEY (
        corrects_progress_fact_id, transfer_id, journey_id
    ) REFERENCES transfer.progress_fact (progress_fact_id, transfer_id, journey_id)
        ON DELETE RESTRICT,
    CONSTRAINT transfer_correction_value_shape CHECK (
        (correction_kind = 'OCCURRENCE_TIME' AND corrected_occurred_at IS NOT NULL
            AND corrected_result_code IS NULL)
        OR
        (correction_kind = 'REPORTED_RESULT' AND corrected_occurred_at IS NULL
            AND corrected_result_code IS NOT NULL)
    ),
    CONSTRAINT transfer_correction_aggregate_sequence_unique UNIQUE (journey_id, aggregate_sequence)
);

CREATE INDEX transfer_progress_correction_history_idx
    ON transfer.progress_correction (transfer_id, aggregate_sequence);

CREATE INDEX transfer_progress_history_idx
    ON transfer.progress_fact (transfer_id, aggregate_sequence);

CREATE TABLE transfer.progress_command_receipt (
    scope_digest char(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest char(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state varchar(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status integer,
    result_reference uuid REFERENCES transfer.transfer_record (transfer_id) ON DELETE RESTRICT,
    result_version bigint,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT transfer_progress_receipt_result_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND response_status IS NULL
            AND result_reference IS NULL AND result_version IS NULL)
        OR
        (receipt_state = 'COMPLETED' AND response_status BETWEEN 200 AND 299
            AND result_reference IS NOT NULL AND result_version > 1)
    )
);
