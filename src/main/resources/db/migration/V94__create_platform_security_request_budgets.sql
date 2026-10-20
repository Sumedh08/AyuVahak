CREATE SCHEMA IF NOT EXISTS platform_security;

-- Fixed hash slots bound storage cardinality. Colliding identities share capacity and may be
-- conservatively throttled, but cannot obtain a fresh budget by changing a source address.
CREATE TABLE platform_security.request_budget_bucket (
    budget_dimension VARCHAR(16) NOT NULL,
    budget_class VARCHAR(24) NOT NULL,
    bucket_slot SMALLINT NOT NULL,
    tokens_micros BIGINT NOT NULL,
    last_refill_at TIMESTAMPTZ NOT NULL,
    configured_limit INTEGER NOT NULL,
    PRIMARY KEY (budget_dimension, budget_class, bucket_slot),
    CONSTRAINT request_budget_dimension_ck CHECK (
        budget_dimension IN ('PRINCIPAL', 'SCOPE', 'PARTNER', 'OPERATION')
    ),
    CONSTRAINT request_budget_class_ck CHECK (
        budget_class IN (
            'ALL', 'ANONYMOUS', 'READ', 'SENSITIVE_WRITE', 'ADMIN', 'SSE', 'FEDERATION', 'EXCHANGE'
        )
    ),
    CONSTRAINT request_budget_dimension_class_ck CHECK (
        (budget_dimension IN ('PRINCIPAL', 'SCOPE') AND budget_class = 'ALL')
        OR (budget_dimension = 'PARTNER' AND budget_class = 'ALL')
        OR (budget_dimension = 'OPERATION' AND budget_class <> 'ALL')
    ),
    CONSTRAINT request_budget_slot_ck CHECK (bucket_slot >= 0 AND bucket_slot < 16384),
    CONSTRAINT request_budget_tokens_ck CHECK (tokens_micros >= 0),
    CONSTRAINT request_budget_limit_ck CHECK (configured_limit > 0)
);

COMMENT ON TABLE platform_security.request_budget_bucket IS
    'Hashed, deployment-wide HTTP abuse budgets. At most 10 x 16,384 rows exist; identities are never persisted.';
