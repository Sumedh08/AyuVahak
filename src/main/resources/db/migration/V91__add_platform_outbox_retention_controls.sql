CREATE TABLE platform_eventing.retention_hold (
    hold_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    purpose_code VARCHAR(40) NOT NULL,
    review_by TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_kind VARCHAR(16) NOT NULL,
    created_by_issuer VARCHAR(256) NOT NULL,
    created_by_identifier_type VARCHAR(64) NOT NULL,
    created_by_identifier VARCHAR(256) NOT NULL,
    released_at TIMESTAMPTZ,
    release_reason_code VARCHAR(40),
    released_by_kind VARCHAR(16),
    released_by_issuer VARCHAR(256),
    released_by_identifier_type VARCHAR(64),
    released_by_identifier VARCHAR(256),
    CONSTRAINT event_retention_hold_purpose_ck CHECK (
        purpose_code IN ('LEGAL_REVIEW', 'SAFETY_INVESTIGATION', 'UNRESOLVED_RECONCILIATION')
    ),
    CONSTRAINT event_retention_hold_release_ck CHECK (
        (released_at IS NULL AND release_reason_code IS NULL AND released_by_kind IS NULL
            AND released_by_issuer IS NULL AND released_by_identifier_type IS NULL
            AND released_by_identifier IS NULL)
        OR
        (released_at IS NOT NULL AND release_reason_code IS NOT NULL AND released_by_kind IS NOT NULL
            AND released_by_issuer IS NOT NULL AND released_by_identifier_type IS NOT NULL
            AND released_by_identifier IS NOT NULL)
    ),
    CONSTRAINT event_retention_hold_release_reason_ck CHECK (
        release_reason_code IS NULL OR release_reason_code IN (
            'REVIEW_COMPLETE', 'INVESTIGATION_CLOSED', 'RECONCILIATION_COMPLETE'
        )
    )
);

CREATE INDEX event_retention_hold_active_event_idx
    ON platform_eventing.retention_hold (event_id)
    WHERE released_at IS NULL;

CREATE TABLE platform_eventing.retention_purge_receipt (
    event_id UUID NOT NULL,
    delivery_generation INTEGER NOT NULL CHECK (delivery_generation > 0),
    purged_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, delivery_generation)
);

COMMENT ON TABLE platform_eventing.retention_hold IS
    'Owner-controlled, reviewable holds that block delivered-event retention purge; hold rows are retained after release.';
COMMENT ON TABLE platform_eventing.retention_purge_receipt IS
    'Owner-local tombstones for delivered-event purges, used only for restore reapplication; contains no event payload.';

CREATE TABLE platform_eventing.retention_reapplication_receipt (
    restore_id UUID PRIMARY KEY,
    completed_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE platform_eventing.retention_reapplication_receipt IS
    'Owner-local proof that Platform retention controls were reapplied before serving a restored database.';
