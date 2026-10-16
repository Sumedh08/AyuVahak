ALTER TABLE transfer.capacity_reservation_request
    DROP CONSTRAINT capacity_reservation_request_transfer_id_key,
    DROP CONSTRAINT transfer_capacity_reservation_operation_is_transfer;

CREATE TABLE transfer.active_destination (
    transfer_id uuid PRIMARY KEY
        REFERENCES transfer.transfer_record (transfer_id) ON DELETE RESTRICT,
    organization_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    reservation_operation_id uuid NOT NULL,
    source_destination_change_id uuid,
    version bigint NOT NULL CHECK (version > 0),
    updated_at timestamptz NOT NULL
);

INSERT INTO transfer.active_destination (
    transfer_id, organization_id, facility_id, reservation_operation_id,
    source_destination_change_id, version, updated_at
)
SELECT t.transfer_id, d.receiving_organization_id, d.receiving_facility_id,
       t.transfer_id, NULL, 1, t.created_at
FROM transfer.transfer_record t
JOIN transfer.request_decision d ON d.transfer_id = t.transfer_id;

CREATE TABLE transfer.destination_change_operation (
    operation_id uuid PRIMARY KEY,
    transfer_id uuid NOT NULL
        REFERENCES transfer.transfer_record (transfer_id) ON DELETE RESTRICT,
    transport_request_id uuid NOT NULL,
    old_organization_id uuid NOT NULL,
    old_facility_id uuid NOT NULL,
    proposed_organization_id uuid NOT NULL,
    proposed_facility_id uuid NOT NULL,
    recommendation_id uuid NOT NULL,
    referral_revision bigint NOT NULL CHECK (referral_revision > 0),
    reason_code varchar(64) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    status varchar(40) NOT NULL CHECK (status IN (
        'AWAITING_RECEIVER_DECISIONS', 'CAPACITY_RESERVATION_PENDING',
        'CAPACITY_RESERVATION_UNKNOWN', 'TRANSPORT_ACK_PENDING', 'TRANSPORT_OUTCOME_UNKNOWN',
        'OLD_HOLD_RELEASE_PENDING', 'PROPOSED_HOLD_RELEASE_PENDING',
        'RECONCILIATION_REQUIRED', 'RESOLVED'
    )),
    capacity_reservation_operation_id uuid NOT NULL,
    capacity_reservation_outcome varchar(16) NOT NULL CHECK (capacity_reservation_outcome IN (
        'PENDING', 'RESERVED', 'CONFLICT', 'UNKNOWN', 'UNAVAILABLE', 'INVALID', 'UNCONFIGURED'
    )),
    old_reservation_operation_id uuid NOT NULL,
    old_hold_release_operation_id uuid NOT NULL,
    proposed_hold_release_operation_id uuid NOT NULL,
    transport_operation_id uuid NOT NULL,
    provider_outcome varchar(32) CHECK (provider_outcome IS NULL OR provider_outcome IN (
        'ACCEPTED', 'REJECTED', 'OUTCOME_UNKNOWN', 'RECONCILIATION_REQUIRED'
    )),
    initiated_by_kind varchar(16) NOT NULL CHECK (initiated_by_kind IN ('HUMAN', 'SERVICE')),
    initiated_by_issuer varchar(256) NOT NULL,
    initiated_by_identifier_type varchar(16) NOT NULL,
    initiated_by_identifier varchar(512) NOT NULL,
    origin_organization_id uuid NOT NULL,
    origin_facility_id uuid NOT NULL,
    correlation_id varchar(64) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT transfer_destination_change_actor_type CHECK (
        (initiated_by_kind = 'HUMAN' AND initiated_by_identifier_type = 'subject') OR
        (initiated_by_kind = 'SERVICE' AND initiated_by_identifier_type = 'client_id')
    ),
    CONSTRAINT transfer_destination_change_distinct_holds CHECK (
        capacity_reservation_operation_id <> old_reservation_operation_id
        AND old_hold_release_operation_id <> proposed_hold_release_operation_id
    )
);

CREATE UNIQUE INDEX transfer_destination_change_one_unresolved_idx
    ON transfer.destination_change_operation (transfer_id)
    WHERE status <> 'RESOLVED';

CREATE INDEX transfer_destination_change_recovery_idx
    ON transfer.destination_change_operation (status, updated_at, operation_id);

CREATE TABLE transfer.destination_change_assessment (
    assessment_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL
        REFERENCES transfer.destination_change_operation (operation_id) ON DELETE RESTRICT,
    assessment_kind varchar(32) NOT NULL CHECK (
        assessment_kind IN ('CLINICAL_SUITABILITY', 'OPERATIONAL_READINESS')
    ),
    outcome varchar(24) NOT NULL CHECK (outcome IN ('SUPPORTS', 'DOES_NOT_SUPPORT', 'INDETERMINATE')),
    reason_code varchar(64) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    evidence_reference varchar(128),
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind IN ('HUMAN', 'SERVICE')),
    recorded_by_issuer varchar(256) NOT NULL,
    recorded_by_identifier_type varchar(16) NOT NULL,
    recorded_by_identifier varchar(512) NOT NULL,
    authority_code varchar(64) NOT NULL,
    organization_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT transfer_destination_change_assessment_once UNIQUE (operation_id, assessment_kind),
    CONSTRAINT transfer_destination_change_assessment_actor_type CHECK (
        (recorded_by_kind = 'HUMAN' AND recorded_by_identifier_type = 'subject') OR
        (recorded_by_kind = 'SERVICE' AND recorded_by_identifier_type = 'client_id')
    ),
    CONSTRAINT transfer_destination_change_assessment_authority CHECK (
        (assessment_kind = 'CLINICAL_SUITABILITY' AND authority_code = 'ClinicalSuitabilityAuthority') OR
        (assessment_kind = 'OPERATIONAL_READINESS' AND authority_code = 'OperationalReadinessAuthority')
    )
);

CREATE TABLE transfer.destination_change_decision (
    operation_id uuid PRIMARY KEY
        REFERENCES transfer.destination_change_operation (operation_id) ON DELETE RESTRICT,
    decision_kind varchar(16) NOT NULL CHECK (decision_kind IN ('ACCEPTED', 'REJECTED')),
    reason_code varchar(64) CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    recorded_by_kind varchar(16) NOT NULL CHECK (recorded_by_kind IN ('HUMAN', 'SERVICE')),
    recorded_by_issuer varchar(256) NOT NULL,
    recorded_by_identifier_type varchar(16) NOT NULL,
    recorded_by_identifier varchar(512) NOT NULL,
    organization_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT transfer_destination_change_decision_shape CHECK (
        (decision_kind = 'ACCEPTED' AND reason_code IS NULL) OR
        (decision_kind = 'REJECTED' AND reason_code IS NOT NULL)
    ),
    CONSTRAINT transfer_destination_change_decision_actor_type CHECK (
        (recorded_by_kind = 'HUMAN' AND recorded_by_identifier_type = 'subject') OR
        (recorded_by_kind = 'SERVICE' AND recorded_by_identifier_type = 'client_id')
    )
);

CREATE TABLE transfer.destination_change_receipt (
    scope_digest char(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest char(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    operation_id uuid NOT NULL,
    receipt_state varchar(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT transfer_destination_change_receipt_operation_fk
        FOREIGN KEY (operation_id)
        REFERENCES transfer.destination_change_operation (operation_id) DEFERRABLE INITIALLY DEFERRED
);
