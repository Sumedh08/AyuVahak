CREATE TABLE analytics.dhis2_export_target_tuple_claim (
    partner_key VARCHAR(96) NOT NULL,
    data_element_uid VARCHAR(64) NOT NULL,
    period_code VARCHAR(8) NOT NULL,
    org_unit_uid VARCHAR(64) NOT NULL,
    category_option_combo_uid VARCHAR(64),
    attribute_option_combo_uid VARCHAR(64),
    operation_id UUID NOT NULL UNIQUE
        REFERENCES analytics.dhis2_export_operation (operation_id) ON DELETE RESTRICT,
    claimed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT analytics_dhis2_target_tuple_claim_unique UNIQUE NULLS NOT DISTINCT (
        partner_key,
        data_element_uid,
        period_code,
        org_unit_uid,
        category_option_combo_uid,
        attribute_option_combo_uid
    )
);

CREATE TABLE analytics.dhis2_export_reconciliation (
    operation_id UUID PRIMARY KEY
        REFERENCES analytics.dhis2_export_operation (operation_id) ON DELETE RESTRICT,
    resolution VARCHAR(48) NOT NULL CHECK (
        resolution IN ('CONFIRMED_APPLIED', 'CONFIRMED_NOT_APPLIED_AND_QUIESCED')
    ),
    evidence_reference UUID NOT NULL,
    observed_value BIGINT,
    audit_record_id UUID NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT analytics_dhis2_reconciliation_value CHECK (
        (resolution = 'CONFIRMED_APPLIED' AND observed_value IS NOT NULL)
        OR (resolution = 'CONFIRMED_NOT_APPLIED_AND_QUIESCED' AND observed_value IS NULL)
    )
);

-- A duplicate unresolved tuple is an unsafe legacy state; the unique constraint deliberately
-- makes migration fail instead of choosing one operation as authoritative.
INSERT INTO analytics.dhis2_export_target_tuple_claim (
    partner_key,
    data_element_uid,
    period_code,
    org_unit_uid,
    category_option_combo_uid,
    attribute_option_combo_uid,
    operation_id,
    claimed_at
)
SELECT
    partner_key,
    data_element_uid,
    period_code,
    org_unit_uid,
    category_option_combo_uid,
    attribute_option_combo_uid,
    operation_id,
    updated_at
FROM analytics.dhis2_export_operation
WHERE operation_state IN ('SENDING', 'OUTCOME_UNKNOWN', 'RECONCILIATION_REQUIRED');
