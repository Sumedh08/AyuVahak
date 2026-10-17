CREATE TABLE transfer.federated_receiving_request (
    receiving_request_id UUID PRIMARY KEY,
    origin_deployment_id VARCHAR(128) NOT NULL,
    request_operation_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    origin_journey_id UUID NOT NULL,
    origin_facility_id VARCHAR(128) NOT NULL,
    destination_organization_issuer VARCHAR(255) NOT NULL,
    destination_organization_id UUID NOT NULL,
    destination_facility_issuer VARCHAR(255) NOT NULL,
    destination_facility_id UUID NOT NULL,
    capacity_dimension_code VARCHAR(64) NOT NULL,
    capacity_unit VARCHAR(32) NOT NULL,
    capacity_quantity NUMERIC(18, 4) NOT NULL,
    capacity_interval_start TIMESTAMPTZ NOT NULL,
    capacity_interval_end TIMESTAMPTZ NOT NULL,
    request_state VARCHAR(32) NOT NULL,
    clinical_outcome VARCHAR(32),
    clinical_reason_code VARCHAR(64),
    clinical_actor_issuer VARCHAR(512),
    clinical_actor_type VARCHAR(64),
    clinical_actor_id VARCHAR(512),
    clinical_occurred_at TIMESTAMPTZ,
    clinical_recorded_at TIMESTAMPTZ,
    operational_outcome VARCHAR(32),
    operational_reason_code VARCHAR(64),
    operational_actor_issuer VARCHAR(512),
    operational_actor_type VARCHAR(64),
    operational_actor_id VARCHAR(512),
    operational_occurred_at TIMESTAMPTZ,
    operational_recorded_at TIMESTAMPTZ,
    accepted_by_issuer VARCHAR(512),
    accepted_by_type VARCHAR(64),
    accepted_by_id VARCHAR(512),
    accepted_at TIMESTAMPTZ,
    capacity_operation_id UUID UNIQUE,
    capacity_reservation_status VARCHAR(32) NOT NULL,
    capacity_reservation_id UUID,
    capacity_reason_code VARCHAR(64),
    capacity_lifecycle_sequence BIGINT NOT NULL DEFAULT 0,
    request_version BIGINT NOT NULL DEFAULT 1,
    received_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT federated_receiving_peer_operation_unique
        UNIQUE (origin_deployment_id, request_operation_id),
    CONSTRAINT federated_receiving_fingerprint_format
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT federated_receiving_origin_deployment_format
        CHECK (origin_deployment_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT federated_receiving_origin_facility_format
        CHECK (origin_facility_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT federated_receiving_directory_refs
        CHECK (destination_organization_issuer = 'urn:ayuvahak:directory'
           AND destination_facility_issuer = 'urn:ayuvahak:directory'),
    CONSTRAINT federated_receiving_capacity_interval
        CHECK (capacity_interval_end > capacity_interval_start),
    CONSTRAINT federated_receiving_capacity_quantity
        CHECK (capacity_quantity > 0),
    CONSTRAINT federated_receiving_state
        CHECK (request_state IN ('RECEIVED', 'ACCEPTED_UNRESERVED', 'ACCEPTED_RESERVED', 'REJECTED')),
    CONSTRAINT federated_receiving_capacity_state
        CHECK (capacity_reservation_status IN (
            'NOT_REQUESTED', 'PENDING', 'OUTCOME_UNKNOWN', 'RESERVED', 'CONFLICT',
            'UNKNOWN', 'INVALID', 'UNAVAILABLE', 'AT_RISK', 'RELEASED', 'EXPIRED')),
    CONSTRAINT federated_receiving_capacity_identity
        CHECK ((capacity_reservation_status = 'NOT_REQUESTED' AND capacity_operation_id IS NULL)
            OR (capacity_reservation_status <> 'NOT_REQUESTED' AND capacity_operation_id IS NOT NULL)),
    CONSTRAINT federated_receiving_capacity_reservation
        CHECK ((capacity_reservation_status IN ('RESERVED', 'AT_RISK', 'RELEASED', 'EXPIRED')
                AND capacity_reservation_id IS NOT NULL)
            OR (capacity_reservation_status NOT IN ('RESERVED', 'AT_RISK', 'RELEASED', 'EXPIRED')
                AND capacity_reservation_id IS NULL)),
    CONSTRAINT federated_receiving_acceptance
        CHECK ((accepted_at IS NULL AND accepted_by_issuer IS NULL
                AND accepted_by_type IS NULL AND accepted_by_id IS NULL)
            OR (accepted_at IS NOT NULL AND accepted_by_issuer IS NOT NULL
                AND accepted_by_type IS NOT NULL AND accepted_by_id IS NOT NULL)),
    CONSTRAINT federated_receiving_assessment_clinical
        CHECK ((clinical_outcome IS NULL AND clinical_reason_code IS NULL
                AND clinical_actor_issuer IS NULL AND clinical_actor_type IS NULL
                AND clinical_actor_id IS NULL AND clinical_occurred_at IS NULL
                AND clinical_recorded_at IS NULL)
            OR (clinical_outcome IN ('SUPPORTS', 'DOES_NOT_SUPPORT', 'INDETERMINATE')
                AND clinical_reason_code IS NOT NULL AND clinical_actor_issuer IS NOT NULL
                AND clinical_actor_type IS NOT NULL AND clinical_actor_id IS NOT NULL
                AND clinical_occurred_at IS NOT NULL AND clinical_recorded_at IS NOT NULL)),
    CONSTRAINT federated_receiving_assessment_operational
        CHECK ((operational_outcome IS NULL AND operational_reason_code IS NULL
                AND operational_actor_issuer IS NULL AND operational_actor_type IS NULL
                AND operational_actor_id IS NULL AND operational_occurred_at IS NULL
                AND operational_recorded_at IS NULL)
            OR (operational_outcome IN ('SUPPORTS', 'DOES_NOT_SUPPORT', 'INDETERMINATE')
                AND operational_reason_code IS NOT NULL AND operational_actor_issuer IS NOT NULL
                AND operational_actor_type IS NOT NULL AND operational_actor_id IS NOT NULL
                AND operational_occurred_at IS NOT NULL AND operational_recorded_at IS NOT NULL)),
    CONSTRAINT federated_receiving_positive_version
        CHECK (request_version > 0 AND capacity_lifecycle_sequence >= 0)
);

CREATE INDEX federated_receiving_request_status_idx
    ON transfer.federated_receiving_request (request_state, updated_at);

CREATE INDEX federated_receiving_capacity_recovery_idx
    ON transfer.federated_receiving_request (capacity_reservation_status, updated_at)
    WHERE capacity_reservation_status IN ('PENDING', 'OUTCOME_UNKNOWN', 'UNAVAILABLE');

CREATE TABLE transfer.federated_receiving_assessment (
    receiving_request_id UUID NOT NULL
        REFERENCES transfer.federated_receiving_request (receiving_request_id),
    assessment_kind VARCHAR(16) NOT NULL,
    request_version BIGINT NOT NULL,
    assessment_outcome VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    actor_issuer VARCHAR(512) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id VARCHAR(512) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (receiving_request_id, assessment_kind, request_version),
    CONSTRAINT federated_receiving_assessment_kind
        CHECK (assessment_kind IN ('CLINICAL', 'OPERATIONAL')),
    CONSTRAINT federated_receiving_assessment_outcome
        CHECK (assessment_outcome IN ('SUPPORTS', 'DOES_NOT_SUPPORT', 'INDETERMINATE')),
    CONSTRAINT federated_receiving_assessment_reason
        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT federated_receiving_assessment_actor_type
        CHECK (actor_type IN ('HUMAN', 'SERVICE')),
    CONSTRAINT federated_receiving_assessment_version
        CHECK (request_version > 0)
);
