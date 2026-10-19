ALTER TABLE referral.reporting_snapshot
    ADD COLUMN origin_facility_id uuid;

CREATE INDEX referral_reporting_snapshot_facility_scope_period_idx
    ON referral.reporting_snapshot (origin_organization_id, origin_facility_id, period_start, period_end);
