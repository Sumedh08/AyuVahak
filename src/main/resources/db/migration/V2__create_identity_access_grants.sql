CREATE SCHEMA identity_access;

CREATE TABLE identity_access.authorization_grant (
    grant_id UUID PRIMARY KEY,
    principal_kind VARCHAR(16) NOT NULL CHECK (principal_kind IN ('HUMAN', 'SERVICE')),
    principal_issuer TEXT NOT NULL CHECK (length(principal_issuer) > 0),
    principal_identifier_type VARCHAR(16) NOT NULL,
    principal_identifier TEXT NOT NULL CHECK (length(principal_identifier) > 0),
    authority_code VARCHAR(64) NOT NULL
        CHECK (authority_code ~ '^[A-Za-z][A-Za-z0-9]{0,63}$'),
    scope_kind VARCHAR(16) NOT NULL CHECK (scope_kind IN ('PLATFORM', 'ORGANIZATION', 'FACILITY')),
    organization_issuer TEXT,
    organization_identifier_type VARCHAR(64),
    organization_identifier TEXT,
    facility_issuer TEXT,
    facility_identifier_type VARCHAR(64),
    facility_identifier TEXT,
    grant_status VARCHAR(16) NOT NULL CHECK (grant_status IN ('ACTIVE', 'INACTIVE', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT authorization_grant_principal_type_check CHECK (
        (principal_kind = 'HUMAN' AND principal_identifier_type = 'subject') OR
        (principal_kind = 'SERVICE' AND principal_identifier_type = 'client_id')
    ),
    CONSTRAINT authorization_grant_scope_shape_check CHECK (
        (scope_kind = 'PLATFORM' AND organization_issuer IS NULL AND
            organization_identifier_type IS NULL AND organization_identifier IS NULL AND
            facility_issuer IS NULL AND facility_identifier_type IS NULL AND
            facility_identifier IS NULL) OR
        (scope_kind = 'ORGANIZATION' AND organization_issuer IS NOT NULL AND
            organization_identifier_type = 'Organization' AND organization_identifier IS NOT NULL AND
            facility_issuer IS NULL AND facility_identifier_type IS NULL AND facility_identifier IS NULL) OR
        (scope_kind = 'FACILITY' AND organization_issuer IS NOT NULL AND
            organization_identifier_type = 'Organization' AND organization_identifier IS NOT NULL AND
            facility_issuer IS NOT NULL AND facility_identifier_type = 'Facility' AND
            facility_identifier IS NOT NULL)
    ),
    CONSTRAINT authorization_grant_revocation_state_check CHECK (
        (grant_status = 'REVOKED') = (revoked_at IS NOT NULL)
    )
);

CREATE INDEX authorization_grant_actor_authority_scope_idx
    ON identity_access.authorization_grant (
        principal_kind,
        principal_issuer,
        principal_identifier_type,
        principal_identifier,
        authority_code,
        scope_kind,
        grant_status
    );

CREATE UNIQUE INDEX authorization_grant_active_scope_uidx
    ON identity_access.authorization_grant (
        principal_kind,
        principal_issuer,
        principal_identifier_type,
        principal_identifier,
        authority_code,
        scope_kind,
        organization_issuer,
        organization_identifier_type,
        organization_identifier,
        facility_issuer,
        facility_identifier_type,
        facility_identifier
    ) NULLS NOT DISTINCT
    WHERE grant_status = 'ACTIVE';
