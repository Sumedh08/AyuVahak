CREATE TABLE directory.federated_remote_projection (
    source_deployment_id VARCHAR(128) NOT NULL
        CHECK (source_deployment_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    source_context VARCHAR(40) NOT NULL CHECK (source_context = 'Directory'),
    resource_type VARCHAR(40) NOT NULL
        CHECK (resource_type IN ('Organization', 'Facility', 'Location', 'HealthcareService')),
    resource_id VARCHAR(128) NOT NULL
        CHECK (resource_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    organization_resource_id VARCHAR(128)
        CHECK (organization_resource_id IS NULL OR
            organization_resource_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    display_name VARCHAR(256) NOT NULL CHECK (length(btrim(display_name)) > 0),
    source_active BOOLEAN NOT NULL,
    source_version BIGINT NOT NULL CHECK (source_version > 0),
    source_updated_at TIMESTAMPTZ,
    source_policy_version VARCHAR(64) NOT NULL
        CHECK (source_policy_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    partner_key VARCHAR(63) NOT NULL
        CHECK (partner_key ~ '^[A-Z][A-Z0-9_-]{1,62}$'),
    partner_configuration_version INTEGER NOT NULL CHECK (partner_configuration_version > 0),
    fact_fingerprint CHAR(64) NOT NULL CHECK (fact_fingerprint ~ '^[0-9a-f]{64}$'),
    projection_status VARCHAR(16) NOT NULL CHECK (projection_status IN ('CURRENT', 'CONFLICT')),
    conflict_fingerprint CHAR(64)
        CHECK (conflict_fingerprint IS NULL OR conflict_fingerprint ~ '^[0-9a-f]{64}$'),
    observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source_deployment_id, source_context, resource_type, resource_id),
    CONSTRAINT federated_remote_projection_freshness
        CHECK (valid_until > received_at),
    CONSTRAINT federated_remote_projection_conflict_shape
        CHECK ((projection_status = 'CONFLICT') = (conflict_fingerprint IS NOT NULL)),
    CONSTRAINT federated_remote_projection_parent_shape
        CHECK ((resource_type = 'Facility' AND organization_resource_id IS NOT NULL)
            OR (resource_type <> 'Facility' AND organization_resource_id IS NULL))
);

CREATE INDEX federated_remote_projection_scope_idx
    ON directory.federated_remote_projection
        (source_deployment_id, organization_resource_id, resource_type, resource_id);

CREATE INDEX federated_remote_projection_freshness_idx
    ON directory.federated_remote_projection (valid_until, source_deployment_id);
