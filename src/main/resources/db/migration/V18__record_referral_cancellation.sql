ALTER TABLE referral.draft DROP CONSTRAINT referral_draft_state_check;
ALTER TABLE referral.draft
    ADD CONSTRAINT referral_draft_state_check
        CHECK (state IN ('DRAFT', 'SUBMITTED', 'CANCELLED', 'RESOLVED'));

CREATE TABLE referral.cancellation (
    referral_id uuid PRIMARY KEY REFERENCES referral.draft (referral_id) ON DELETE RESTRICT,
    reason_code varchar(64) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    cancelled_by_kind varchar(16) NOT NULL CHECK (cancelled_by_kind IN ('HUMAN', 'SERVICE')),
    cancelled_by_issuer varchar(256) NOT NULL CHECK (length(btrim(cancelled_by_issuer)) > 0),
    cancelled_by_identifier_type varchar(16) NOT NULL,
    cancelled_by_identifier varchar(512) NOT NULL CHECK (length(btrim(cancelled_by_identifier)) > 0),
    referral_version bigint NOT NULL CHECK (referral_version > 0),
    cancelled_at timestamptz NOT NULL,
    CONSTRAINT referral_cancellation_actor_type CHECK (
        (cancelled_by_kind = 'HUMAN' AND cancelled_by_identifier_type = 'subject') OR
        (cancelled_by_kind = 'SERVICE' AND cancelled_by_identifier_type = 'client_id')
    )
);

CREATE FUNCTION referral.reject_cancellation_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Referral cancellation is immutable';
END;
$$;

CREATE TRIGGER referral_cancellation_is_immutable
    BEFORE UPDATE OR DELETE ON referral.cancellation
    FOR EACH ROW EXECUTE FUNCTION referral.reject_cancellation_mutation();
