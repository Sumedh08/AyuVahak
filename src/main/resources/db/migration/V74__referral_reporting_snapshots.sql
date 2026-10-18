CREATE SEQUENCE referral.reporting_snapshot_version_seq AS bigint START WITH 1;

CREATE TABLE referral.reporting_snapshot (
    snapshot_id uuid PRIMARY KEY,
    request_id uuid NOT NULL UNIQUE,
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    origin_organization_id uuid NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    source_cutoff timestamptz NOT NULL,
    completeness varchar(24) NOT NULL CHECK (completeness IN ('COMPLETE', 'PARTIAL')),
    source_version bigint NOT NULL UNIQUE DEFAULT nextval('referral.reporting_snapshot_version_seq'),
    generated_at timestamptz NOT NULL,
    CONSTRAINT referral_reporting_snapshot_period CHECK (period_start < period_end)
);

CREATE INDEX referral_reporting_snapshot_scope_period_idx
    ON referral.reporting_snapshot (origin_organization_id, period_start, period_end);

CREATE TABLE referral.reporting_snapshot_measure (
    snapshot_id uuid NOT NULL
        REFERENCES referral.reporting_snapshot (snapshot_id) ON DELETE RESTRICT,
    measure_key varchar(32) NOT NULL CHECK (measure_key IN ('SUBMITTED_COUNT', 'RESOLVED_COUNT')),
    measure_value bigint CHECK (measure_value IS NULL OR measure_value >= 0),
    PRIMARY KEY (snapshot_id, measure_key)
);

CREATE FUNCTION referral.reject_reporting_snapshot_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Referral reporting snapshots are immutable';
END;
$$;

CREATE TRIGGER referral_reporting_snapshot_is_immutable
    BEFORE UPDATE OR DELETE ON referral.reporting_snapshot
    FOR EACH ROW EXECUTE FUNCTION referral.reject_reporting_snapshot_mutation();

CREATE TRIGGER referral_reporting_snapshot_measure_is_immutable
    BEFORE UPDATE OR DELETE ON referral.reporting_snapshot_measure
    FOR EACH ROW EXECUTE FUNCTION referral.reject_reporting_snapshot_mutation();

CREATE FUNCTION referral.validate_reporting_snapshot_measure() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    snapshot_completeness varchar(24);
BEGIN
    SELECT completeness
      INTO snapshot_completeness
      FROM referral.reporting_snapshot
     WHERE snapshot_id = NEW.snapshot_id;

    IF snapshot_completeness = 'COMPLETE' AND NEW.measure_value IS NULL THEN
        RAISE EXCEPTION 'Complete Referral reporting snapshots require numeric measures';
    END IF;
    IF snapshot_completeness <> 'COMPLETE' AND NEW.measure_value IS NOT NULL THEN
        RAISE EXCEPTION 'Incomplete Referral reporting snapshots cannot contain numeric measures';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER referral_reporting_snapshot_measure_matches_completeness
    BEFORE INSERT ON referral.reporting_snapshot_measure
    FOR EACH ROW EXECUTE FUNCTION referral.validate_reporting_snapshot_measure();
