CREATE TABLE transfer.federated_origin_receiving_receipt (
  scope_digest CHAR(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
  idempotency_key_digest CHAR(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
  request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
  receipt_state VARCHAR(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
  response_status INTEGER,
  result_reference UUID REFERENCES transfer.federated_origin_receiving_attempt(origin_attempt_id)
    ON DELETE RESTRICT,
  result_version BIGINT,
  recorded_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (scope_digest, idempotency_key_digest),
  CONSTRAINT federated_origin_receiving_receipt_result_shape CHECK (
    (receipt_state = 'IN_PROGRESS' AND response_status IS NULL
      AND result_reference IS NULL AND result_version IS NULL)
    OR
    (receipt_state = 'COMPLETED' AND response_status BETWEEN 200 AND 299
      AND result_reference IS NOT NULL AND result_version > 0)
  )
);
