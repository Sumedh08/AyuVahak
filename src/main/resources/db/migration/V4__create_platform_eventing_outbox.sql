CREATE SCHEMA platform_eventing;

CREATE TABLE platform_eventing.internal_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    producer_context VARCHAR(63) NOT NULL,
    aggregate_ref VARCHAR(512),
    aggregate_sequence BIGINT,
    envelope JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    current_delivery_generation INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT internal_event_aggregate_position_pair
        CHECK ((aggregate_ref IS NULL) = (aggregate_sequence IS NULL)),
    CONSTRAINT internal_event_aggregate_sequence_positive
        CHECK (aggregate_sequence IS NULL OR aggregate_sequence > 0),
    CONSTRAINT internal_event_generation_positive
        CHECK (current_delivery_generation > 0),
    CONSTRAINT internal_event_envelope_object
        CHECK (jsonb_typeof(envelope) = 'object')
);

CREATE UNIQUE INDEX internal_event_aggregate_sequence_unique
    ON platform_eventing.internal_event (producer_context, aggregate_ref, aggregate_sequence)
    WHERE aggregate_ref IS NOT NULL;

CREATE TABLE platform_eventing.outbox_delivery (
    event_id UUID NOT NULL,
    delivery_generation INTEGER NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    last_failure_category VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT outbox_delivery_pk PRIMARY KEY (event_id, delivery_generation),
    CONSTRAINT outbox_delivery_event_fk
        FOREIGN KEY (event_id) REFERENCES platform_eventing.internal_event (event_id),
    CONSTRAINT outbox_delivery_generation_positive CHECK (delivery_generation > 0),
    CONSTRAINT outbox_delivery_attempts_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT outbox_delivery_lease_generation_nonnegative CHECK (lease_generation >= 0),
    CONSTRAINT outbox_delivery_state_valid
        CHECK (state IN ('PENDING', 'LEASED', 'DELIVERED', 'STALLED', 'UNHANDLED')),
    CONSTRAINT outbox_delivery_lease_shape
        CHECK ((state = 'LEASED') = (lease_until IS NOT NULL))
);

CREATE INDEX outbox_delivery_due_idx
    ON platform_eventing.outbox_delivery (next_attempt_at, created_at)
    WHERE state = 'PENDING';

CREATE INDEX outbox_delivery_expired_lease_idx
    ON platform_eventing.outbox_delivery (lease_until, created_at)
    WHERE state = 'LEASED';
