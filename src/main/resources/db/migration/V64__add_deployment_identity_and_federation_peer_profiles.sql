CREATE SCHEMA platform_federation;

CREATE TABLE platform_federation.deployment_identity (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    deployment_id VARCHAR(128) NOT NULL
        CHECK (deployment_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    installation_binding_digest CHAR(64) NOT NULL
        CHECK (installation_binding_digest ~ '^[0-9a-f]{64}$'),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version = 1),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE platform_federation.deployment_identity_conflict (
    conflict_id UUID PRIMARY KEY,
    configured_deployment_id VARCHAR(128) NOT NULL
        CHECK (configured_deployment_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    registered_deployment_id VARCHAR(128) NOT NULL
        CHECK (registered_deployment_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    conflict_code VARCHAR(40) NOT NULL
        CHECK (conflict_code IN ('DEPLOYMENT_ID_MISMATCH', 'INSTALLATION_BINDING_MISMATCH')),
    observed_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE platform_exchange.partner_profile_version
    ADD COLUMN peer_deployment_id VARCHAR(128),
    ADD COLUMN federation_protocol_profile VARCHAR(128),
    ADD CONSTRAINT partner_profile_peer_deployment_id_check
        CHECK (peer_deployment_id IS NULL OR
            peer_deployment_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    ADD CONSTRAINT partner_profile_federation_profile_check
        CHECK ((peer_deployment_id IS NULL) = (federation_protocol_profile IS NULL)),
    ADD CONSTRAINT partner_profile_federation_profile_value_check
        CHECK (federation_protocol_profile IS NULL OR
            federation_protocol_profile = 'AyuVahak-Federation-v1'),
    ADD CONSTRAINT partner_profile_peer_trust_authentication_check
        CHECK (peer_deployment_id IS NULL OR authentication_method <> 'NONE'),
    ADD CONSTRAINT partner_profile_peer_retry_check
        CHECK (peer_deployment_id IS NULL OR (retry_class = 'NEVER' AND max_attempts = 1)),
    ADD CONSTRAINT partner_profile_peer_reference_unique
        UNIQUE (profile_version_id, peer_deployment_id);

CREATE UNIQUE INDEX partner_profile_one_active_federation_peer
    ON platform_exchange.partner_profile_version (peer_deployment_id)
    WHERE state = 'ACTIVE' AND peer_deployment_id IS NOT NULL;

CREATE TABLE platform_exchange.partner_profile_peer_facility_scope (
    profile_version_id UUID NOT NULL,
    peer_deployment_id VARCHAR(128) NOT NULL,
    scope_issuer TEXT NOT NULL CHECK (length(btrim(scope_issuer)) BETWEEN 1 AND 512),
    facility_identifier TEXT NOT NULL CHECK (length(btrim(facility_identifier)) BETWEEN 1 AND 256),
    PRIMARY KEY (profile_version_id, scope_issuer, facility_identifier),
    FOREIGN KEY (profile_version_id, peer_deployment_id)
        REFERENCES platform_exchange.partner_profile_version(profile_version_id, peer_deployment_id)
);

ALTER TABLE platform_exchange.partner_profile_operation
    DROP CONSTRAINT partner_profile_operation_owner_context_check,
    DROP CONSTRAINT partner_profile_operation_protocol_owner_check;

ALTER TABLE platform_exchange.partner_profile_operation
    ADD CONSTRAINT partner_profile_operation_owner_context_check
        CHECK (owner_context IN ('DIRECTORY', 'REFERRAL', 'TRANSPORT', 'CAPACITY', 'MATCHING', 'TRANSFER')),
    ADD CONSTRAINT partner_profile_operation_protocol_owner_check
        CHECK (
            (owner_context IN ('TRANSPORT', 'CAPACITY', 'MATCHING', 'TRANSFER') AND protocol = 'JSON')
            OR (owner_context = 'REFERRAL' AND protocol = 'FHIR_R4_JSON')
            OR owner_context = 'DIRECTORY'
        );
