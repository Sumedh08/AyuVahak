CREATE SCHEMA directory;

CREATE TABLE directory.organization (
    organization_id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    parent_organization_id UUID REFERENCES directory.organization (organization_id)
        ON DELETE RESTRICT,
    source_issuer TEXT NOT NULL CHECK (length(btrim(source_issuer)) > 0),
    source_resource_type VARCHAR(64) NOT NULL
        CHECK (source_resource_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,63}$'),
    source_reference TEXT NOT NULL CHECK (length(btrim(source_reference)) > 0),
    provenance_method VARCHAR(80) NOT NULL CHECK (provenance_method ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    provenance_observed_at TIMESTAMPTZ NOT NULL,
    provenance_recorded_at TIMESTAMPTZ NOT NULL,
    provenance_actor_kind VARCHAR(16) NOT NULL CHECK (provenance_actor_kind IN ('HUMAN', 'SERVICE')),
    provenance_actor_issuer TEXT NOT NULL CHECK (length(btrim(provenance_actor_issuer)) > 0),
    provenance_actor_identifier_type VARCHAR(16) NOT NULL,
    provenance_actor_identifier TEXT NOT NULL CHECK (length(btrim(provenance_actor_identifier)) > 0),
    provenance_reason_code VARCHAR(80) NOT NULL CHECK (provenance_reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    lifecycle_status VARCHAR(16) NOT NULL CHECK (lifecycle_status IN ('ACTIVE', 'INACTIVE')),
    version BIGINT NOT NULL CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT organization_parent_not_self CHECK (
        parent_organization_id IS NULL OR parent_organization_id <> organization_id
    ),
    CONSTRAINT organization_provenance_actor_type CHECK (
        (provenance_actor_kind = 'HUMAN' AND provenance_actor_identifier_type = 'subject') OR
        (provenance_actor_kind = 'SERVICE' AND provenance_actor_identifier_type = 'client_id')
    )
);

CREATE INDEX organization_active_name_idx
    ON directory.organization (lower(name), organization_id)
    WHERE lifecycle_status = 'ACTIVE';

CREATE TABLE directory.facility (
    facility_id UUID PRIMARY KEY,
    managing_organization_id UUID NOT NULL
        REFERENCES directory.organization (organization_id) ON DELETE RESTRICT,
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    source_issuer TEXT NOT NULL CHECK (length(btrim(source_issuer)) > 0),
    source_resource_type VARCHAR(64) NOT NULL
        CHECK (source_resource_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,63}$'),
    source_reference TEXT NOT NULL CHECK (length(btrim(source_reference)) > 0),
    provenance_method VARCHAR(80) NOT NULL CHECK (provenance_method ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    provenance_observed_at TIMESTAMPTZ NOT NULL,
    provenance_recorded_at TIMESTAMPTZ NOT NULL,
    provenance_actor_kind VARCHAR(16) NOT NULL CHECK (provenance_actor_kind IN ('HUMAN', 'SERVICE')),
    provenance_actor_issuer TEXT NOT NULL CHECK (length(btrim(provenance_actor_issuer)) > 0),
    provenance_actor_identifier_type VARCHAR(16) NOT NULL,
    provenance_actor_identifier TEXT NOT NULL CHECK (length(btrim(provenance_actor_identifier)) > 0),
    provenance_reason_code VARCHAR(80) NOT NULL CHECK (provenance_reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    lifecycle_status VARCHAR(16) NOT NULL CHECK (lifecycle_status IN ('ACTIVE', 'INACTIVE')),
    version BIGINT NOT NULL CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT facility_provenance_actor_type CHECK (
        (provenance_actor_kind = 'HUMAN' AND provenance_actor_identifier_type = 'subject') OR
        (provenance_actor_kind = 'SERVICE' AND provenance_actor_identifier_type = 'client_id')
    )
);

CREATE INDEX facility_active_organization_idx
    ON directory.facility (managing_organization_id, facility_id)
    WHERE lifecycle_status = 'ACTIVE';

CREATE TABLE directory.organization_revision (
    revision_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES directory.organization (organization_id)
        ON DELETE RESTRICT,
    superseded_version BIGINT NOT NULL CHECK (superseded_version > 0),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    changed_at TIMESTAMPTZ NOT NULL,
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    UNIQUE (organization_id, superseded_version)
);

CREATE TABLE directory.facility_revision (
    revision_id UUID PRIMARY KEY,
    facility_id UUID NOT NULL REFERENCES directory.facility (facility_id)
        ON DELETE RESTRICT,
    superseded_version BIGINT NOT NULL CHECK (superseded_version > 0),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    changed_at TIMESTAMPTZ NOT NULL,
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    UNIQUE (facility_id, superseded_version)
);

CREATE TABLE directory.command_receipt (
    scope_digest CHAR(64) NOT NULL CHECK (scope_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key_digest CHAR(64) NOT NULL CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_state VARCHAR(16) NOT NULL CHECK (receipt_state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status SMALLINT,
    result_reference UUID,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT directory_command_receipt_pk
        PRIMARY KEY (scope_digest, idempotency_key_digest),
    CONSTRAINT directory_command_receipt_completion_shape CHECK (
        (receipt_state = 'IN_PROGRESS' AND response_status IS NULL AND result_reference IS NULL) OR
        (receipt_state = 'COMPLETED' AND response_status BETWEEN 200 AND 299
            AND result_reference IS NOT NULL)
    )
);
