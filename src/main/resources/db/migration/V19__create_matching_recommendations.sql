CREATE SCHEMA matching;

CREATE TABLE matching.recommendation_snapshot (
    recommendation_id uuid PRIMARY KEY,
    referral_id uuid NOT NULL,
    referral_revision bigint NOT NULL CHECK (referral_revision > 0),
    origin_organization_id uuid NOT NULL,
    origin_facility_id uuid,
    matching_policy_code varchar(64) NOT NULL CHECK (
        matching_policy_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'
    ),
    matching_policy_version bigint NOT NULL CHECK (matching_policy_version > 0),
    mapping_policy_code varchar(64) NOT NULL CHECK (
        mapping_policy_code ~ '^[A-Z][A-Z0-9._-]{0,63}$'
    ),
    mapping_policy_version bigint NOT NULL CHECK (mapping_policy_version > 0),
    evaluation_outcome varchar(16) NOT NULL CHECK (
        evaluation_outcome IN ('COMPLETE', 'INDETERMINATE')
    ),
    generated_at timestamptz NOT NULL,
    applicable_until timestamptz NOT NULL CHECK (applicable_until > generated_at),
    snapshot_document jsonb NOT NULL CHECK (jsonb_typeof(snapshot_document) = 'object'),
    CONSTRAINT matching_snapshot_json_identity CHECK (
        snapshot_document ->> 'id' IS NOT NULL
        AND snapshot_document ->> 'referralId' IS NOT NULL
    )
);

CREATE INDEX matching_snapshot_referral_history_idx
    ON matching.recommendation_snapshot (
        origin_organization_id,
        origin_facility_id,
        referral_id,
        generated_at DESC,
        recommendation_id
    );

CREATE FUNCTION matching.reject_snapshot_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Recommendation Snapshots are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER matching_snapshot_immutable
    BEFORE UPDATE ON matching.recommendation_snapshot
    FOR EACH ROW EXECUTE FUNCTION matching.reject_snapshot_update();

CREATE TABLE matching.command_receipt (
    scope_digest char(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest char(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state varchar(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status integer,
    result_reference uuid,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT matching_receipt_result_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND response_status IS NULL AND result_reference IS NULL)
        OR
        (receipt_state = 'COMPLETED' AND response_status BETWEEN 200 AND 299
            AND result_reference IS NOT NULL)
    ),
    CONSTRAINT matching_receipt_snapshot_fk
        FOREIGN KEY (result_reference)
        REFERENCES matching.recommendation_snapshot (recommendation_id)
        ON DELETE RESTRICT
);
