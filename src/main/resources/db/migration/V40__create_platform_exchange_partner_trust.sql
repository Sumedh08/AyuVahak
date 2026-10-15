CREATE SCHEMA platform_exchange;

CREATE TABLE platform_exchange.partner_profile (
    partner_id UUID PRIMARY KEY,
    partner_key VARCHAR(64) NOT NULL UNIQUE,
    current_version INTEGER NOT NULL DEFAULT 0 CHECK (current_version >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE platform_exchange.partner_profile_version (
    profile_version_id UUID PRIMARY KEY,
    partner_id UUID NOT NULL REFERENCES platform_exchange.partner_profile(partner_id),
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    state VARCHAR(16) NOT NULL CHECK (
        state IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'REVOKED', 'SUPERSEDED')
    ),
    organization_issuer TEXT NOT NULL CHECK (length(btrim(organization_issuer)) > 0),
    organization_identifier TEXT NOT NULL CHECK (length(btrim(organization_identifier)) > 0),
    endpoint_origin TEXT NOT NULL,
    base_path TEXT NOT NULL,
    authentication_method VARCHAR(40) NOT NULL CHECK (
        authentication_method IN ('NONE', 'OAUTH_CLIENT_CREDENTIALS', 'OAUTH_PRIVATE_KEY_JWT', 'MUTUAL_TLS')
    ),
    credential_reference TEXT,
    token_origin TEXT,
    token_path TEXT,
    oauth_issuer TEXT,
    oauth_audience TEXT,
    oauth_client_id TEXT,
    inbound_principal_issuer TEXT,
    inbound_principal_client_id TEXT,
    tls_policy VARCHAR(24) NOT NULL CHECK (tls_policy IN ('SYSTEM_CA', 'PARTNER_CA')),
    tls_trust_reference TEXT,
    connect_timeout_millis INTEGER NOT NULL CHECK (connect_timeout_millis BETWEEN 100 AND 120000),
    response_timeout_millis INTEGER NOT NULL CHECK (response_timeout_millis BETWEEN 100 AND 120000),
    operation_timeout_millis INTEGER NOT NULL CHECK (operation_timeout_millis BETWEEN 100 AND 300000),
    max_response_bytes INTEGER NOT NULL CHECK (max_response_bytes BETWEEN 1 AND 20000000),
    max_decompressed_bytes INTEGER NOT NULL CHECK (max_decompressed_bytes BETWEEN 1 AND 40000000),
    max_pages INTEGER NOT NULL CHECK (max_pages BETWEEN 1 AND 100),
    max_resources INTEGER NOT NULL CHECK (max_resources BETWEEN 1 AND 10000),
    max_nesting_depth INTEGER NOT NULL CHECK (max_nesting_depth BETWEEN 1 AND 64),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
    retry_class VARCHAR(32) NOT NULL CHECK (retry_class IN ('NEVER', 'IDEMPOTENT_READ')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (partner_id, version_number),
    UNIQUE (partner_id, profile_version_id),
    CHECK ((tls_policy = 'PARTNER_CA') = (tls_trust_reference IS NOT NULL)),
    CHECK ((inbound_principal_issuer IS NULL) = (inbound_principal_client_id IS NULL)),
    CHECK (
        (authentication_method = 'NONE'
            AND credential_reference IS NULL
            AND token_origin IS NULL
            AND token_path IS NULL
            AND oauth_issuer IS NULL
            AND oauth_audience IS NULL
            AND oauth_client_id IS NULL)
        OR (authentication_method IN ('OAUTH_CLIENT_CREDENTIALS', 'OAUTH_PRIVATE_KEY_JWT')
            AND credential_reference IS NOT NULL
            AND token_origin IS NOT NULL
            AND token_path IS NOT NULL
            AND oauth_issuer IS NOT NULL
            AND oauth_audience IS NOT NULL
            AND oauth_client_id IS NOT NULL)
        OR (authentication_method = 'MUTUAL_TLS'
            AND credential_reference IS NOT NULL
            AND token_origin IS NULL
            AND token_path IS NULL
            AND oauth_issuer IS NULL
            AND oauth_audience IS NULL
            AND oauth_client_id IS NULL)
    )
);

CREATE UNIQUE INDEX partner_profile_one_active_version
    ON platform_exchange.partner_profile_version (partner_id)
    WHERE state = 'ACTIVE';

CREATE UNIQUE INDEX partner_profile_one_active_inbound_principal
    ON platform_exchange.partner_profile_version
        (inbound_principal_issuer, inbound_principal_client_id)
    WHERE state = 'ACTIVE' AND inbound_principal_client_id IS NOT NULL;

CREATE TABLE platform_exchange.partner_profile_operation (
    profile_version_id UUID NOT NULL
        REFERENCES platform_exchange.partner_profile_version(profile_version_id),
    owner_context VARCHAR(16) NOT NULL CHECK (owner_context IN ('DIRECTORY', 'REFERRAL')),
    operation_code VARCHAR(64) NOT NULL,
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    protocol VARCHAR(32) NOT NULL CHECK (protocol = 'FHIR_R4_JSON'),
    profile TEXT NOT NULL CHECK (length(btrim(profile)) > 0),
    PRIMARY KEY (profile_version_id, owner_context, operation_code, direction, profile)
);

CREATE INDEX partner_profile_active_operation_lookup
    ON platform_exchange.partner_profile_operation (owner_context, operation_code, direction, profile);

CREATE TABLE platform_exchange.partner_admin_command_receipt (
    scope_digest CHAR(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest CHAR(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state VARCHAR(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INTEGER,
    result_version_id UUID REFERENCES platform_exchange.partner_profile_version(profile_version_id),
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope_digest, idempotency_key_digest),
    CHECK ((receipt_state = 'IN_PROGRESS' AND response_status IS NULL AND result_version_id IS NULL)
        OR (receipt_state = 'COMPLETED' AND response_status IS NOT NULL AND result_version_id IS NOT NULL))
);
