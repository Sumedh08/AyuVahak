CREATE SCHEMA platform_notification;

CREATE TABLE platform_notification.scope_cursor (
    scope_key VARCHAR(64) PRIMARY KEY,
    cursor_epoch UUID NOT NULL,
    current_sequence BIGINT NOT NULL DEFAULT 0,
    current_cursor UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_scope_key_sha256 CHECK (scope_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT notification_scope_sequence_nonnegative CHECK (current_sequence >= 0)
);

CREATE TABLE platform_notification.cursor_position (
    cursor_id UUID PRIMARY KEY,
    scope_key VARCHAR(64) NOT NULL,
    cursor_epoch UUID NOT NULL,
    sequence_number BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_cursor_scope_fk
        FOREIGN KEY (scope_key) REFERENCES platform_notification.scope_cursor (scope_key) ON DELETE CASCADE,
    CONSTRAINT notification_cursor_sequence_nonnegative CHECK (sequence_number >= 0),
    CONSTRAINT notification_cursor_scope_epoch_sequence_unique
        UNIQUE (scope_key, cursor_epoch, sequence_number)
);

CREATE TABLE platform_notification.notification_projection (
    event_id UUID PRIMARY KEY,
    cursor_id UUID NOT NULL UNIQUE,
    scope_key VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id UUID NOT NULL,
    owner_version BIGINT NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_projection_cursor_fk
        FOREIGN KEY (cursor_id) REFERENCES platform_notification.cursor_position (cursor_id) ON DELETE CASCADE,
    CONSTRAINT notification_projection_scope_fk
        FOREIGN KEY (scope_key) REFERENCES platform_notification.scope_cursor (scope_key) ON DELETE CASCADE,
    CONSTRAINT notification_projection_sequence_positive CHECK (sequence_number > 0),
    CONSTRAINT notification_projection_version_positive CHECK (owner_version > 0),
    CONSTRAINT notification_projection_resource_type_valid
        CHECK (resource_type IN ('referral', 'transfer', 'capacity-reservation', 'transport',
            'transport-cancellation', 'transport-destination-change')),
    CONSTRAINT notification_projection_event_type_shape
        CHECK (event_type ~ '^[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*\.v[1-9][0-9]*$')
);

CREATE UNIQUE INDEX notification_projection_scope_sequence_unique
    ON platform_notification.notification_projection (scope_key, sequence_number);

CREATE INDEX notification_projection_scope_projected_idx
    ON platform_notification.notification_projection (scope_key, projected_at, sequence_number);

CREATE TABLE platform_notification.event_receipt (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX notification_event_receipt_retention_idx
    ON platform_notification.event_receipt (processed_at);

COMMENT ON SCHEMA platform_notification IS
    'Platform-owned bounded non-authoritative operational notification projection; no owner payload is retained.';

COMMENT ON TABLE platform_notification.event_receipt IS
    'Bounded internal-event deduplication receipts; retained separately from the shorter notification window.';
