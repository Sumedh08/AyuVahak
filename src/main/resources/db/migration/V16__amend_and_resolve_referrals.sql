ALTER TABLE referral.draft DROP CONSTRAINT referral_draft_state_check;
ALTER TABLE referral.draft
    ADD CONSTRAINT referral_draft_state_check CHECK (state IN ('DRAFT', 'SUBMITTED', 'RESOLVED'));

ALTER TABLE referral.submitted_revision
    ADD COLUMN reason_code varchar(64) CHECK (
        reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'
    );

CREATE TABLE referral.resolution (
    referral_id uuid PRIMARY KEY REFERENCES referral.draft (referral_id) ON DELETE RESTRICT,
    outcome varchar(16) NOT NULL CHECK (outcome IN ('FULFILLED', 'UNFULFILLED')),
    reason_code varchar(64) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    resolved_by_kind varchar(16) NOT NULL CHECK (resolved_by_kind IN ('HUMAN', 'SERVICE')),
    resolved_by_issuer varchar(256) NOT NULL CHECK (length(btrim(resolved_by_issuer)) > 0),
    resolved_by_identifier_type varchar(16) NOT NULL,
    resolved_by_identifier varchar(512) NOT NULL CHECK (length(btrim(resolved_by_identifier)) > 0),
    referral_version bigint NOT NULL CHECK (referral_version > 0),
    resolved_at timestamptz NOT NULL,
    CONSTRAINT referral_resolution_actor_type CHECK (
        (resolved_by_kind = 'HUMAN' AND resolved_by_identifier_type = 'subject') OR
        (resolved_by_kind = 'SERVICE' AND resolved_by_identifier_type = 'client_id')
    )
);

CREATE FUNCTION referral.reject_resolution_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Referral resolution is immutable';
END;
$$;

CREATE TRIGGER referral_resolution_is_immutable
    BEFORE UPDATE OR DELETE ON referral.resolution
    FOR EACH ROW EXECUTE FUNCTION referral.reject_resolution_mutation();
