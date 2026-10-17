CREATE TABLE transfer.federated_origin_receiving_attempt (
  origin_attempt_id UUID PRIMARY KEY,
  journey_id UUID NOT NULL REFERENCES transfer.journey(journey_id) ON DELETE RESTRICT,
  attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
  origin_deployment_id VARCHAR(128) NOT NULL,
  peer_deployment_id VARCHAR(128) NOT NULL,
  partner_key VARCHAR(128) NOT NULL,
  partner_configuration_version INTEGER NOT NULL CHECK (partner_configuration_version > 0),
  operation_id UUID NOT NULL,
  request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
  command_fingerprint CHAR(64) NOT NULL CHECK (command_fingerprint ~ '^[0-9a-f]{64}$'),
  request_body BYTEA NOT NULL CHECK (octet_length(request_body) BETWEEN 1 AND 65536),
  operation_state VARCHAR(32) NOT NULL CHECK (operation_state IN (
    'PENDING', 'SENT', 'OUTCOME_UNKNOWN', 'RESOLVED', 'REJECTED', 'RECONCILIATION_REQUIRED')),
  remote_outcome VARCHAR(32) CHECK (remote_outcome IN (
    'RECEIVED', 'ACCEPTED_RESERVED', 'ACCEPTED_UNRESERVED', 'REJECTED')),
  remote_receiving_request_id UUID,
  remote_capacity_reservation_id UUID,
  remote_version BIGINT NOT NULL DEFAULT 0 CHECK (remote_version >= 0),
  attempt_version BIGINT NOT NULL CHECK (attempt_version > 0),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT federated_origin_receiving_distinct_deployments
    CHECK (origin_deployment_id <> peer_deployment_id),
  CONSTRAINT federated_origin_receiving_journey_attempt_unique
    UNIQUE (journey_id, attempt_number),
  CONSTRAINT federated_origin_receiving_operation_unique
    UNIQUE (origin_deployment_id, operation_id),
  CONSTRAINT federated_origin_receiving_remote_result_consistent
    CHECK ((remote_outcome IS NULL) = (remote_receiving_request_id IS NULL))
);

CREATE INDEX federated_origin_receiving_blocking_idx
  ON transfer.federated_origin_receiving_attempt (journey_id, attempt_number DESC)
  WHERE operation_state IN ('PENDING', 'SENT', 'OUTCOME_UNKNOWN', 'RECONCILIATION_REQUIRED')
     OR remote_outcome IN ('RECEIVED', 'ACCEPTED_RESERVED', 'ACCEPTED_UNRESERVED');
