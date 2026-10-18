CREATE TABLE transfer.reporting_snapshot (
    source_snapshot_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    measure_key VARCHAR(64) NOT NULL CHECK (
        measure_key IN ('TRANSFER_ACCEPTED', 'TRANSFER_COMPLETED', 'TRANSFER_UNFULFILLED')
    ),
    attribution VARCHAR(16) NOT NULL CHECK (attribution IN ('ORIGIN', 'DESTINATION')),
    scope_kind VARCHAR(16) NOT NULL CHECK (scope_kind IN ('ORGANIZATION', 'FACILITY')),
    organization_id UUID NOT NULL,
    facility_id UUID,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    source_cutoff TIMESTAMPTZ NOT NULL,
    owner_contract_version VARCHAR(64) NOT NULL,
    completeness VARCHAR(24) NOT NULL CHECK (completeness = 'COMPLETE'),
    value BIGINT NOT NULL CHECK (value >= 0),
    generated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT transfer_reporting_period_nonempty CHECK (period_end > period_start),
    CONSTRAINT transfer_reporting_facility_scope_shape CHECK (
        (scope_kind = 'ORGANIZATION' AND facility_id IS NULL)
        OR (scope_kind = 'FACILITY' AND facility_id IS NOT NULL)
    ),
    CONSTRAINT transfer_reporting_destination_unfulfilled_unsupported CHECK (
        NOT (measure_key = 'TRANSFER_UNFULFILLED' AND attribution = 'DESTINATION')
    )
);

CREATE INDEX transfer_reporting_journey_origin_scope_idx
    ON transfer.journey (origin_organization_id, origin_facility_id, journey_id);

CREATE INDEX transfer_reporting_accepted_period_idx
    ON transfer.request_decision (
        occurred_at, recorded_at, receiving_organization_id, receiving_facility_id, transfer_id
    ) WHERE decision_kind = 'ACCEPTED';

CREATE INDEX transfer_reporting_completion_period_idx
    ON transfer.progress_fact (occurred_at, recorded_at, organization_id, facility_id, transfer_id)
    WHERE fact_kind = 'COMPLETION';

CREATE INDEX transfer_reporting_closure_period_idx
    ON transfer.journey_closure (occurred_at, recorded_at, organization_id, facility_id, journey_id);

CREATE INDEX transfer_reporting_federated_receiver_period_idx
    ON transfer.federated_receiving_request (
        accepted_at, updated_at, destination_organization_id, destination_facility_id
    ) WHERE request_state IN ('ACCEPTED_RESERVED', 'ACCEPTED_UNRESERVED');

CREATE INDEX transfer_reporting_federated_origin_period_idx
    ON transfer.federated_origin_receiving_attempt (remote_accepted_at, updated_at, journey_id)
    WHERE remote_outcome IN ('ACCEPTED_RESERVED', 'ACCEPTED_UNRESERVED');
