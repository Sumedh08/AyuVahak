CREATE TABLE directory.location (
    location_id UUID PRIMARY KEY,
    facility_id UUID NOT NULL REFERENCES directory.facility (facility_id) ON DELETE RESTRICT,
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    coordinates geography(Point, 4326),
    is_primary BOOLEAN NOT NULL,
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
    version BIGINT NOT NULL CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT location_coordinate_bounds CHECK (
        coordinates IS NULL OR (
            ST_X(coordinates::geometry) BETWEEN -180 AND 180
            AND ST_Y(coordinates::geometry) BETWEEN -90 AND 90
        )
    ),
    CONSTRAINT location_provenance_actor_type CHECK (
        (provenance_actor_kind = 'HUMAN' AND provenance_actor_identifier_type = 'subject') OR
        (provenance_actor_kind = 'SERVICE' AND provenance_actor_identifier_type = 'client_id')
    )
);

CREATE UNIQUE INDEX location_single_primary_idx
    ON directory.location (facility_id)
    WHERE is_primary;

CREATE INDEX location_facility_idx
    ON directory.location (facility_id, location_id);

CREATE INDEX location_coordinates_gist_idx
    ON directory.location USING GIST (coordinates);

CREATE TABLE directory.location_revision (
    revision_id UUID PRIMARY KEY,
    location_id UUID NOT NULL REFERENCES directory.location (location_id) ON DELETE RESTRICT,
    superseded_version BIGINT NOT NULL CHECK (superseded_version > 0),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    changed_at TIMESTAMPTZ NOT NULL,
    reason_code VARCHAR(80) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    UNIQUE (location_id, superseded_version)
);
