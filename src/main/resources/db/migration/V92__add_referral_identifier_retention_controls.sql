CREATE TABLE referral.retention_hold (
    hold_id UUID PRIMARY KEY,
    referral_id UUID NOT NULL,
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
    CONSTRAINT referral_retention_hold_purpose_ck CHECK (
        purpose_code IN ('LEGAL_REVIEW', 'SAFETY_INVESTIGATION', 'UNRESOLVED_RECONCILIATION')
    ),
    CONSTRAINT referral_retention_hold_release_ck CHECK (
        (released_at IS NULL AND release_reason_code IS NULL AND released_by_kind IS NULL
            AND released_by_issuer IS NULL AND released_by_identifier_type IS NULL
            AND released_by_identifier IS NULL)
        OR
        (released_at IS NOT NULL AND release_reason_code IS NOT NULL AND released_by_kind IS NOT NULL
            AND released_by_issuer IS NOT NULL AND released_by_identifier_type IS NOT NULL
            AND released_by_identifier IS NOT NULL)
    ),
    CONSTRAINT referral_retention_hold_release_reason_ck CHECK (
        release_reason_code IS NULL OR release_reason_code IN (
            'REVIEW_COMPLETE', 'INVESTIGATION_CLOSED', 'RECONCILIATION_COMPLETE'
        )
    )
);

CREATE INDEX referral_retention_hold_active_referral_idx
    ON referral.retention_hold (referral_id)
    WHERE released_at IS NULL;

CREATE TABLE referral.identifier_restriction (
    referral_id UUID PRIMARY KEY,
    restriction_id UUID NOT NULL UNIQUE,
    reason_code VARCHAR(40) NOT NULL,
    restricted_at TIMESTAMPTZ NOT NULL,
    restricted_by_kind VARCHAR(16) NOT NULL,
    restricted_by_issuer VARCHAR(256) NOT NULL,
    restricted_by_identifier_type VARCHAR(64) NOT NULL,
    restricted_by_identifier VARCHAR(256) NOT NULL,
    CONSTRAINT referral_identifier_restriction_reason_ck CHECK (
        reason_code IN ('RETENTION_REVIEW_COMPLETED', 'LEGAL_OR_POLICY_REQUIRED')
    )
);

COMMENT ON TABLE referral.identifier_restriction IS
    'Referral-owned access restriction overlay for closed-case PatientReference; immutable submitted revisions and audit history are not rewritten.';

CREATE TABLE referral.retention_reapplication_receipt (
    restore_id UUID PRIMARY KEY,
    completed_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE referral.retention_reapplication_receipt IS
    'Owner-local proof that Referral retention controls were reapplied before serving a restored database.';
