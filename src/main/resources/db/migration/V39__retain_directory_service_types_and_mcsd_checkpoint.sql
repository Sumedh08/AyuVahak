ALTER TABLE directory.healthcare_service
    ADD COLUMN service_types JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(service_types) = 'array');

CREATE TABLE directory.mcsd_source_checkpoint (
    source_issuer TEXT PRIMARY KEY CHECK (length(btrim(source_issuer)) > 0),
    change_cursor TEXT NOT NULL CHECK (
        length(btrim(change_cursor)) BETWEEN 1 AND 2048
        AND change_cursor = btrim(change_cursor)
        AND change_cursor !~ '[[:cntrl:]]'
    ),
    updated_at TIMESTAMPTZ NOT NULL
);
