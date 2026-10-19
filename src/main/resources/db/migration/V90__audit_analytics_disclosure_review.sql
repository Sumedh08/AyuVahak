CREATE TABLE analytics.disclosure_reservation_review (
    reservation_id UUID PRIMARY KEY
        REFERENCES analytics.disclosure_reservation (reservation_id) ON DELETE RESTRICT,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVED', 'WITHHELD')),
    evidence_reference UUID NOT NULL,
    reviewer_issuer VARCHAR(256) NOT NULL,
    reviewer_resource_type VARCHAR(64) NOT NULL,
    reviewer_identity VARCHAR(128) NOT NULL,
    audit_record_id UUID NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL
);
