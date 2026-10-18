CREATE TABLE analytics.federated_aggregate_mapping_head (
    mapping_key VARCHAR(64) PRIMARY KEY,
    active_version INTEGER,
    lifecycle_state VARCHAR(16) NOT NULL CHECK (lifecycle_state IN ('ACTIVE', 'INACTIVE')),
    audit_record_id UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT analytics_federated_aggregate_mapping_head_version_state CHECK (
        (lifecycle_state = 'ACTIVE' AND active_version IS NOT NULL AND active_version > 0)
        OR lifecycle_state = 'INACTIVE'
    )
);

CREATE TABLE analytics.federated_aggregate_mapping_version (
    mapping_key VARCHAR(64) NOT NULL REFERENCES analytics.federated_aggregate_mapping_head (mapping_key) ON DELETE RESTRICT,
    mapping_version INTEGER NOT NULL CHECK (mapping_version > 0),
    direction VARCHAR(16) NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    indicator_key VARCHAR(64) NOT NULL,
    indicator_version INTEGER NOT NULL CHECK (indicator_version > 0),
    definition_fingerprint CHAR(64) NOT NULL CHECK (definition_fingerprint ~ '^[0-9a-f]{64}$'),
    source_organization_issuer VARCHAR(256) NOT NULL,
    source_organization_reference_type VARCHAR(64) NOT NULL CHECK (source_organization_reference_type = 'Organization'),
    source_organization_id VARCHAR(256) NOT NULL,
    partner_key VARCHAR(96) NOT NULL,
    peer_deployment_id VARCHAR(128) NOT NULL,
    partner_configuration_version INTEGER NOT NULL CHECK (partner_configuration_version > 0),
    scope_organization_issuer VARCHAR(256) NOT NULL,
    scope_organization_reference_type VARCHAR(64) NOT NULL CHECK (scope_organization_reference_type = 'Organization'),
    scope_organization_id VARCHAR(256) NOT NULL,
    disclosure_policy_key VARCHAR(64) NOT NULL,
    disclosure_output_key VARCHAR(128) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ,
    mapping_fingerprint CHAR(64) NOT NULL CHECK (mapping_fingerprint ~ '^[0-9a-f]{64}$'),
    audit_record_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (mapping_key, mapping_version),
    FOREIGN KEY (indicator_key, indicator_version)
        REFERENCES analytics.indicator_definition_version (indicator_key, definition_version)
        ON DELETE RESTRICT,
    CONSTRAINT analytics_federated_aggregate_mapping_effectivity CHECK (
        effective_until IS NULL OR effective_from < effective_until
    )
);

CREATE INDEX analytics_federated_aggregate_mapping_lookup_idx
    ON analytics.federated_aggregate_mapping_version (
        direction, peer_deployment_id, indicator_key, indicator_version,
        source_organization_issuer, source_organization_id, effective_from, effective_until
    );

CREATE TABLE analytics.federated_aggregate_publication (
    operation_id UUID PRIMARY KEY,
    operation_fingerprint CHAR(64) NOT NULL CHECK (operation_fingerprint ~ '^[0-9a-f]{64}$'),
    source_deployment_id VARCHAR(128) NOT NULL,
    peer_deployment_id VARCHAR(128) NOT NULL,
    source_snapshot_id UUID NOT NULL REFERENCES analytics.aggregate_snapshot (snapshot_id) ON DELETE RESTRICT,
    revision_series_id UUID NOT NULL,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    mapping_key VARCHAR(64) NOT NULL,
    mapping_version INTEGER NOT NULL CHECK (mapping_version > 0),
    partner_key VARCHAR(96) NOT NULL,
    partner_configuration_version INTEGER NOT NULL CHECK (partner_configuration_version > 0),
    definition_fingerprint CHAR(64) NOT NULL CHECK (definition_fingerprint ~ '^[0-9a-f]{64}$'),
    content_fingerprint CHAR(64) NOT NULL CHECK (content_fingerprint ~ '^[0-9a-f]{64}$'),
    disclosure_reservation_id UUID REFERENCES analytics.disclosure_reservation (reservation_id) ON DELETE RESTRICT,
    operation_state VARCHAR(32) NOT NULL CHECK (
        operation_state IN ('READY', 'SENDING', 'ACKNOWLEDGED', 'INCOMPATIBLE', 'OUTCOME_UNKNOWN', 'REJECTED', 'WITHHELD')
    ),
    result_class VARCHAR(48) NOT NULL CHECK (result_class ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    receipt_id UUID,
    audit_record_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (mapping_key, mapping_version)
        REFERENCES analytics.federated_aggregate_mapping_version (mapping_key, mapping_version)
        ON DELETE RESTRICT,
    UNIQUE (source_deployment_id, peer_deployment_id, source_snapshot_id, mapping_key, mapping_version)
);

CREATE INDEX analytics_federated_aggregate_publication_state_idx
    ON analytics.federated_aggregate_publication (operation_state, updated_at);

CREATE TABLE analytics.federated_aggregate_receipt (
    receipt_id UUID PRIMARY KEY,
    source_deployment_id VARCHAR(128) NOT NULL,
    operation_id UUID NOT NULL,
    operation_fingerprint CHAR(64) NOT NULL CHECK (operation_fingerprint ~ '^[0-9a-f]{64}$'),
    content_fingerprint CHAR(64) NOT NULL CHECK (content_fingerprint ~ '^[0-9a-f]{64}$'),
    source_snapshot_id UUID NOT NULL,
    revision_series_id UUID NOT NULL,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    indicator_key VARCHAR(64) NOT NULL,
    indicator_version INTEGER NOT NULL CHECK (indicator_version > 0),
    definition_fingerprint CHAR(64) NOT NULL CHECK (definition_fingerprint ~ '^[0-9a-f]{64}$'),
    source_fingerprint CHAR(64) NOT NULL CHECK (source_fingerprint ~ '^[0-9a-f]{64}$'),
    source_organization_issuer VARCHAR(256) NOT NULL,
    source_organization_reference_type VARCHAR(64) NOT NULL CHECK (source_organization_reference_type = 'Organization'),
    source_organization_id VARCHAR(256) NOT NULL,
    target_organization_id VARCHAR(256) NOT NULL,
    period_start_local DATE NOT NULL,
    period_end_exclusive_local DATE NOT NULL,
    period_timezone VARCHAR(128) NOT NULL,
    period_start_utc TIMESTAMPTZ NOT NULL,
    period_end_exclusive_utc TIMESTAMPTZ NOT NULL,
    timezone_rules_version VARCHAR(64) NOT NULL,
    completeness VARCHAR(24) NOT NULL CHECK (completeness = 'COMPLETE'),
    aggregate_value BIGINT NOT NULL CHECK (aggregate_value >= 0),
    attribution VARCHAR(16) NOT NULL CHECK (attribution IN ('NONE', 'ORIGIN', 'DESTINATION')),
    computed_at TIMESTAMPTZ NOT NULL,
    peer_key VARCHAR(96) NOT NULL,
    peer_configuration_version INTEGER NOT NULL CHECK (peer_configuration_version > 0),
    mapping_key VARCHAR(64),
    mapping_version INTEGER,
    receipt_state VARCHAR(24) NOT NULL CHECK (receipt_state IN ('COMBINABLE', 'INCOMPATIBLE')),
    result_class VARCHAR(48) NOT NULL CHECK (result_class ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    received_at TIMESTAMPTZ NOT NULL,
    audit_record_id UUID,
    CONSTRAINT analytics_federated_aggregate_receipt_mapping_pair CHECK (
        (mapping_key IS NULL AND mapping_version IS NULL)
        OR (mapping_key IS NOT NULL AND mapping_version > 0)
    ),
    UNIQUE (source_deployment_id, source_snapshot_id),
    UNIQUE (source_deployment_id, operation_id),
    FOREIGN KEY (mapping_key, mapping_version)
        REFERENCES analytics.federated_aggregate_mapping_version (mapping_key, mapping_version)
        ON DELETE RESTRICT
);

CREATE INDEX analytics_federated_aggregate_receipt_combine_idx
    ON analytics.federated_aggregate_receipt (
        indicator_key, indicator_version, target_organization_id, period_start_utc, receipt_state
    );
