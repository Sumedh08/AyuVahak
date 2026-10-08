ALTER TABLE directory.facility
    ADD CONSTRAINT facility_id_organization_unique
        UNIQUE (facility_id, managing_organization_id);

ALTER TABLE directory.location
    ADD CONSTRAINT location_id_facility_unique
        UNIQUE (location_id, facility_id);

CREATE TABLE directory.healthcare_service (
    healthcare_service_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    location_id UUID,
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
    CONSTRAINT healthcare_service_facility_owner_fk
        FOREIGN KEY (facility_id, organization_id)
        REFERENCES directory.facility (facility_id, managing_organization_id)
        ON DELETE RESTRICT,
    CONSTRAINT healthcare_service_location_owner_fk
        FOREIGN KEY (location_id, facility_id)
        REFERENCES directory.location (location_id, facility_id)
        ON DELETE RESTRICT,
    CONSTRAINT healthcare_service_id_facility_unique UNIQUE (healthcare_service_id, facility_id),
    CONSTRAINT healthcare_service_provenance_actor_type CHECK (
        (provenance_actor_kind = 'HUMAN' AND provenance_actor_identifier_type = 'subject') OR
        (provenance_actor_kind = 'SERVICE' AND provenance_actor_identifier_type = 'client_id')
    )
);

CREATE INDEX healthcare_service_active_facility_idx
    ON directory.healthcare_service (facility_id, healthcare_service_id)
    WHERE lifecycle_status = 'ACTIVE';

CREATE TABLE directory.healthcare_service_revision (
    revision_id UUID PRIMARY KEY,
    healthcare_service_id UUID NOT NULL
        REFERENCES directory.healthcare_service (healthcare_service_id) ON DELETE RESTRICT,
    superseded_version BIGINT NOT NULL CHECK (superseded_version > 0),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    changed_at TIMESTAMPTZ NOT NULL,
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    UNIQUE (healthcare_service_id, superseded_version)
);

CREATE TABLE directory.care_capability (
    care_capability_id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9._-]{0,63}$'),
    display_name VARCHAR(200) NOT NULL CHECK (length(btrim(display_name)) BETWEEN 1 AND 200),
    parent_capability_id UUID REFERENCES directory.care_capability (care_capability_id)
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
    CONSTRAINT care_capability_parent_not_self CHECK (
        parent_capability_id IS NULL OR parent_capability_id <> care_capability_id
    ),
    CONSTRAINT care_capability_provenance_actor_type CHECK (
        (provenance_actor_kind = 'HUMAN' AND provenance_actor_identifier_type = 'subject') OR
        (provenance_actor_kind = 'SERVICE' AND provenance_actor_identifier_type = 'client_id')
    )
);

CREATE INDEX care_capability_active_code_idx
    ON directory.care_capability (code, care_capability_id)
    WHERE lifecycle_status = 'ACTIVE';

CREATE TABLE directory.care_capability_revision (
    revision_id UUID PRIMARY KEY,
    care_capability_id UUID NOT NULL
        REFERENCES directory.care_capability (care_capability_id) ON DELETE RESTRICT,
    superseded_version BIGINT NOT NULL CHECK (superseded_version > 0),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    changed_at TIMESTAMPTZ NOT NULL,
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    UNIQUE (care_capability_id, superseded_version)
);

CREATE TABLE directory.facility_capability_assertion (
    assertion_id UUID PRIMARY KEY,
    facility_id UUID NOT NULL REFERENCES directory.facility (facility_id) ON DELETE RESTRICT,
    care_capability_id UUID NOT NULL
        REFERENCES directory.care_capability (care_capability_id) ON DELETE RESTRICT,
    healthcare_service_id UUID,
    verification_status VARCHAR(16) NOT NULL CHECK (
        verification_status IN ('UNVERIFIED', 'VERIFIED', 'DISPUTED', 'WITHDRAWN', 'SUPERSEDED')
    ),
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
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ,
    version BIGINT NOT NULL CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT facility_capability_assertion_interval CHECK (
        effective_until IS NULL OR effective_until > effective_from
    ),
    CONSTRAINT facility_capability_assertion_service_owner_fk
        FOREIGN KEY (healthcare_service_id, facility_id)
        REFERENCES directory.healthcare_service (healthcare_service_id, facility_id)
        ON DELETE RESTRICT,
    CONSTRAINT facility_capability_assertion_provenance_actor_type CHECK (
        (provenance_actor_kind = 'HUMAN' AND provenance_actor_identifier_type = 'subject') OR
        (provenance_actor_kind = 'SERVICE' AND provenance_actor_identifier_type = 'client_id')
    )
);

CREATE INDEX facility_capability_assertion_current_idx
    ON directory.facility_capability_assertion
        (facility_id, care_capability_id, verification_status, effective_from, effective_until);

CREATE TABLE directory.facility_capability_assertion_revision (
    revision_id UUID PRIMARY KEY,
    assertion_id UUID NOT NULL
        REFERENCES directory.facility_capability_assertion (assertion_id) ON DELETE RESTRICT,
    superseded_version BIGINT NOT NULL CHECK (superseded_version > 0),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    changed_at TIMESTAMPTZ NOT NULL,
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    UNIQUE (assertion_id, superseded_version)
);
