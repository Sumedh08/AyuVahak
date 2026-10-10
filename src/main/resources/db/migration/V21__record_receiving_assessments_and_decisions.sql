ALTER TABLE transfer.request_attempt
    DROP CONSTRAINT request_attempt_request_status_check,
    ADD CONSTRAINT transfer_request_status_check
        CHECK (request_status IN ('PENDING', 'ACCEPTED', 'REJECTED'));

CREATE TABLE transfer.receiving_assessment (
    assessment_id uuid PRIMARY KEY,
    request_attempt_id uuid NOT NULL REFERENCES transfer.request_attempt (request_attempt_id)
        ON DELETE RESTRICT,
    assessment_kind varchar(32) NOT NULL
        CHECK (assessment_kind IN ('CLINICAL_SUITABILITY', 'OPERATIONAL_READINESS')),
    outcome varchar(24) NOT NULL CHECK (outcome IN ('SUPPORTS', 'DOES_NOT_SUPPORT', 'INDETERMINATE')),
    reason_code varchar(64) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    evidence_reference varchar(128) CHECK (
        evidence_reference IS NULL OR evidence_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
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
        (assessment_kind = 'CLINICAL_SUITABILITY' AND authority_code = 'ClinicalSuitabilityAuthority') OR
        (assessment_kind = 'OPERATIONAL_READINESS' AND authority_code = 'OperationalReadinessAuthority')
    ),
    receiving_organization_id uuid NOT NULL,
    receiving_facility_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    request_version bigint NOT NULL CHECK (request_version > 1),
    CONSTRAINT transfer_assessment_actor_type CHECK (
        (recorded_by_kind = 'HUMAN' AND recorded_by_identifier_type = 'subject') OR
        (recorded_by_kind = 'SERVICE' AND recorded_by_identifier_type = 'client_id')
    ),
    CONSTRAINT transfer_assessment_version_unique
        UNIQUE (request_attempt_id, assessment_kind, request_version)
);

CREATE INDEX transfer_assessment_latest_idx
    ON transfer.receiving_assessment (request_attempt_id, assessment_kind, request_version DESC);

CREATE TABLE transfer.transfer_record (
    transfer_id uuid PRIMARY KEY,
    journey_id uuid NOT NULL REFERENCES transfer.journey (journey_id) ON DELETE RESTRICT,
    request_attempt_id uuid NOT NULL UNIQUE
        REFERENCES transfer.request_attempt (request_attempt_id) ON DELETE RESTRICT,
    transfer_status varchar(32) NOT NULL CHECK (transfer_status = 'AWAITING_RESERVATION'),
    created_at timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version = 1)
);

CREATE TABLE transfer.request_decision (
    decision_id uuid PRIMARY KEY,
    request_attempt_id uuid NOT NULL UNIQUE REFERENCES transfer.request_attempt (request_attempt_id)
        ON DELETE RESTRICT,
    decision_kind varchar(16) NOT NULL CHECK (decision_kind IN ('ACCEPTED', 'REJECTED')),
    reason_code varchar(64) CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    transfer_id uuid UNIQUE REFERENCES transfer.transfer_record (transfer_id) ON DELETE RESTRICT,
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind IN ('HUMAN', 'SERVICE')),
    recorded_by_issuer varchar(256) NOT NULL CHECK (length(btrim(recorded_by_issuer)) > 0),
    recorded_by_identifier_type varchar(16) NOT NULL,
    recorded_by_identifier varchar(512) NOT NULL CHECK (
        length(btrim(recorded_by_identifier)) > 0
        AND recorded_by_identifier = btrim(recorded_by_identifier)
        AND recorded_by_identifier !~ '[[:cntrl:]]'
    ),
    authority_code varchar(64) NOT NULL CHECK (authority_code = 'AcceptanceAuthority'),
    receiving_organization_id uuid NOT NULL,
    receiving_facility_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    request_version bigint NOT NULL CHECK (request_version > 1),
    CONSTRAINT transfer_decision_actor_type CHECK (
        (recorded_by_kind = 'HUMAN' AND recorded_by_identifier_type = 'subject') OR
        (recorded_by_kind = 'SERVICE' AND recorded_by_identifier_type = 'client_id')
    ),
    CONSTRAINT transfer_decision_shape CHECK (
        (decision_kind = 'ACCEPTED' AND reason_code IS NULL AND transfer_id IS NOT NULL) OR
        (decision_kind = 'REJECTED' AND reason_code IS NOT NULL AND transfer_id IS NULL)
    )
);
