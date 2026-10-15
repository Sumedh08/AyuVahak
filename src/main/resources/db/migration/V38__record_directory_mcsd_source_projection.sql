CREATE TABLE directory.mcsd_source_projection (
    projection_id UUID PRIMARY KEY,
    source_issuer TEXT NOT NULL CHECK (length(btrim(source_issuer)) > 0),
    source_resource_type VARCHAR(64) NOT NULL
        CHECK (source_resource_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,63}$'),
    source_reference TEXT NOT NULL CHECK (
        length(btrim(source_reference)) > 0
        AND source_reference = btrim(source_reference)
        AND source_reference !~ '[[:cntrl:]]'
    ),
    concept_type VARCHAR(40) NOT NULL CHECK (
        concept_type IN ('ORGANIZATION', 'FACILITY', 'LOCATION', 'HEALTHCARE_SERVICE')
    ),
    concept_id UUID,
    source_profile_uri TEXT NOT NULL CHECK (length(btrim(source_profile_uri)) > 0),
    source_version_id TEXT,
    source_updated_at TIMESTAMPTZ,
    source_updated_at_ordered_revision BOOLEAN NOT NULL,
    projection_fingerprint CHAR(64) NOT NULL CHECK (
        projection_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    source_active BOOLEAN NOT NULL,
    last_outcome VARCHAR(32) NOT NULL CHECK (last_outcome IN (
        'CREATE', 'UPDATE', 'UNCHANGED', 'STALE', 'CONFLICT', 'DEACTIVATE',
        'RECONCILIATION_REQUIRED'
    )),
    version BIGINT NOT NULL CHECK (version > 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT mcsd_projection_ordered_timestamp CHECK (
        NOT source_updated_at_ordered_revision OR source_updated_at IS NOT NULL
    ),
    CONSTRAINT mcsd_projection_source_identity_uq
        UNIQUE (source_issuer, source_resource_type, source_reference)
);

CREATE INDEX mcsd_source_projection_concept_idx
    ON directory.mcsd_source_projection (concept_type, concept_id)
    WHERE concept_id IS NOT NULL;
