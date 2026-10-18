CREATE TABLE analytics.dhis2_mapping_head (
    mapping_key VARCHAR(64) PRIMARY KEY,
    active_version INTEGER,
    lifecycle_state VARCHAR(16) NOT NULL CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE')),
    audit_record_id UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT analytics_dhis2_mapping_head_version_state CHECK (
        (lifecycle_state = 'ACTIVE' AND active_version IS NOT NULL AND active_version > 0)
        OR lifecycle_state = 'INACTIVE'
    )
);

CREATE TABLE analytics.dhis2_mapping_version (
    mapping_key VARCHAR(64) NOT NULL REFERENCES analytics.dhis2_mapping_head (mapping_key) ON DELETE RESTRICT,
    mapping_version INTEGER NOT NULL CHECK (mapping_version > 0),
    indicator_key VARCHAR(64) NOT NULL,
    indicator_version INTEGER NOT NULL,
    organization_issuer VARCHAR(256) NOT NULL,
    organization_reference_type VARCHAR(64) NOT NULL CHECK (organization_reference_type = 'Organization'),
    organization_id UUID NOT NULL,
    partner_key VARCHAR(96) NOT NULL,
    data_element_uid VARCHAR(64) NOT NULL,
    org_unit_uid VARCHAR(64) NOT NULL,
    data_set_uid VARCHAR(64),
    category_option_combo_uid VARCHAR(64),
    attribute_option_combo_uid VARCHAR(64),
    period_format VARCHAR(16) NOT NULL CHECK (period_format IN ('DAILY', 'MONTHLY', 'QUARTERLY', 'ANNUAL')),
    disclosure_policy_key VARCHAR(64) NOT NULL,
    disclosure_output_key VARCHAR(128) NOT NULL,
    exclusive_write_authority BOOLEAN NOT NULL,
    zero_is_significant BOOLEAN NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ,
    mapping_fingerprint CHAR(64) NOT NULL CHECK (mapping_fingerprint ~ '^[0-9a-f]{64}$'),
    audit_record_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (mapping_key, mapping_version),
    FOREIGN KEY (indicator_key, indicator_version)
        REFERENCES analytics.indicator_definition_version (indicator_key, definition_version)
        ON DELETE RESTRICT,
    CONSTRAINT analytics_dhis2_mapping_effectivity CHECK (
        effective_until IS NULL OR effective_from < effective_until
    )
);

CREATE INDEX analytics_dhis2_mapping_indicator_idx
    ON analytics.dhis2_mapping_version (
        organization_id, indicator_key, indicator_version, effective_from, effective_until
    );

CREATE TABLE analytics.dhis2_mapping_disclosure_scope (
    indicator_key VARCHAR(64) NOT NULL,
    indicator_version INTEGER NOT NULL CHECK (indicator_version > 0),
    organization_issuer VARCHAR(256) NOT NULL,
    organization_reference_type VARCHAR(64) NOT NULL CHECK (organization_reference_type = 'Organization'),
    organization_id UUID NOT NULL,
    disclosure_policy_key VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (
        indicator_key, indicator_version, organization_issuer,
        organization_reference_type, organization_id
    ),
    FOREIGN KEY (indicator_key, indicator_version)
        REFERENCES analytics.indicator_definition_version (indicator_key, definition_version)
        ON DELETE RESTRICT
);

CREATE TABLE analytics.dhis2_export_operation (
    operation_id UUID PRIMARY KEY,
    operation_fingerprint CHAR(64) NOT NULL CHECK (operation_fingerprint ~ '^[0-9a-f]{64}$'),
    snapshot_id UUID NOT NULL REFERENCES analytics.aggregate_snapshot (snapshot_id) ON DELETE RESTRICT,
    revision_series_id UUID NOT NULL,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    mapping_key VARCHAR(64) NOT NULL,
    mapping_version INTEGER NOT NULL CHECK (mapping_version > 0),
    partner_key VARCHAR(96) NOT NULL,
    partner_configuration_version INTEGER CHECK (
        partner_configuration_version IS NULL OR partner_configuration_version > 0
    ),
    data_element_uid VARCHAR(64) NOT NULL,
    org_unit_uid VARCHAR(64) NOT NULL,
    period_code VARCHAR(8) NOT NULL,
    category_option_combo_uid VARCHAR(64),
    attribute_option_combo_uid VARCHAR(64),
    data_set_uid VARCHAR(64),
    disclosure_reservation_id UUID REFERENCES analytics.disclosure_reservation (reservation_id) ON DELETE RESTRICT,
    operation_state VARCHAR(32) NOT NULL CHECK (
        operation_state IN (
            'PREPARED', 'SENDING', 'ACKNOWLEDGED', 'OUTCOME_UNKNOWN',
            'RECONCILIATION_REQUIRED', 'REJECTED', 'WITHHELD'
        )
    ),
    result_class VARCHAR(48) NOT NULL CHECK (result_class ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    attempt_count SMALLINT NOT NULL CHECK (attempt_count BETWEEN 0 AND 1),
    imported_count INTEGER CHECK (imported_count IS NULL OR imported_count >= 0),
    updated_count INTEGER CHECK (updated_count IS NULL OR updated_count >= 0),
    ignored_count INTEGER CHECK (ignored_count IS NULL OR ignored_count >= 0),
    deleted_count INTEGER CHECK (deleted_count IS NULL OR deleted_count >= 0),
    audit_record_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (mapping_key, mapping_version)
        REFERENCES analytics.dhis2_mapping_version (mapping_key, mapping_version)
        ON DELETE RESTRICT
);

CREATE INDEX analytics_dhis2_export_snapshot_idx
    ON analytics.dhis2_export_operation (snapshot_id, revision_number, created_at);

CREATE INDEX analytics_dhis2_export_tuple_state_idx
    ON analytics.dhis2_export_operation (
        partner_key, data_element_uid, org_unit_uid, period_code,
        category_option_combo_uid, attribute_option_combo_uid, operation_state
    );
