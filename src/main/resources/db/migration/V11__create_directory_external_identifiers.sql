CREATE TABLE directory.external_identifier (
    identifier_id UUID PRIMARY KEY,
    concept_type VARCHAR(40) NOT NULL CHECK (concept_type IN (
        'ORGANIZATION',
        'FACILITY',
        'LOCATION',
        'HEALTHCARE_SERVICE',
        'CARE_CAPABILITY',
        'FACILITY_CAPABILITY_ASSERTION'
    )),
    concept_id UUID NOT NULL,
    issuer TEXT NOT NULL CHECK (length(btrim(issuer)) > 0),
    resource_type VARCHAR(64) NOT NULL
        CHECK (resource_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,63}$'),
    identifier_value TEXT NOT NULL CHECK (
        length(btrim(identifier_value)) > 0
        AND identifier_value = btrim(identifier_value)
        AND identifier_value !~ '[[:cntrl:]]'
    ),
    lifecycle_status VARCHAR(16) NOT NULL CHECK (
        lifecycle_status IN ('ACTIVE', 'REMOVED', 'SUPERSEDED')
    ),
    supersedes_identifier_id UUID REFERENCES directory.external_identifier(identifier_id)
        ON DELETE RESTRICT,
    provenance_method VARCHAR(80) NOT NULL CHECK (provenance_method ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    provenance_observed_at TIMESTAMPTZ NOT NULL,
    provenance_recorded_at TIMESTAMPTZ NOT NULL,
    provenance_actor_kind VARCHAR(16) NOT NULL CHECK (provenance_actor_kind IN ('HUMAN', 'SERVICE')),
    provenance_actor_issuer TEXT NOT NULL CHECK (length(btrim(provenance_actor_issuer)) > 0),
    provenance_actor_identifier_type VARCHAR(16) NOT NULL,
    provenance_actor_identifier TEXT NOT NULL CHECK (length(btrim(provenance_actor_identifier)) > 0),
    provenance_reason_code VARCHAR(80) NOT NULL CHECK (provenance_reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    version BIGINT NOT NULL CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT external_identifier_not_self_superseded CHECK (
        supersedes_identifier_id IS NULL OR supersedes_identifier_id <> identifier_id
    ),
    CONSTRAINT external_identifier_provenance_actor_type CHECK (
        (provenance_actor_kind = 'HUMAN' AND provenance_actor_identifier_type = 'subject') OR
        (provenance_actor_kind = 'SERVICE' AND provenance_actor_identifier_type = 'client_id')
    )
);

CREATE UNIQUE INDEX external_identifier_active_tuple_uq
    ON directory.external_identifier (concept_type, issuer, resource_type, identifier_value)
    WHERE lifecycle_status = 'ACTIVE';

CREATE INDEX external_identifier_concept_history_idx
    ON directory.external_identifier (concept_type, concept_id, created_at, identifier_id);

CREATE TABLE directory.external_identifier_revision (
    revision_id UUID PRIMARY KEY,
    identifier_id UUID NOT NULL
        REFERENCES directory.external_identifier (identifier_id) ON DELETE RESTRICT,
    superseded_version BIGINT NOT NULL CHECK (superseded_version > 0),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    changed_at TIMESTAMPTZ NOT NULL,
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    UNIQUE (identifier_id, superseded_version)
);
