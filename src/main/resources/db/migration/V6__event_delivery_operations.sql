CREATE TABLE platform_eventing.delivery_command_receipt (
    scope_digest CHAR(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest CHAR(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state VARCHAR(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status SMALLINT,
    event_id UUID REFERENCES platform_eventing.internal_event (event_id),
    delivery_generation INTEGER,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT delivery_command_receipt_pk
        PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT delivery_command_receipt_completion_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND response_status IS NULL
            AND event_id IS NULL AND delivery_generation IS NULL)
        OR
        (receipt_state = 'COMPLETED' AND response_status = 202
            AND event_id IS NOT NULL AND delivery_generation > 0)
    )
);
