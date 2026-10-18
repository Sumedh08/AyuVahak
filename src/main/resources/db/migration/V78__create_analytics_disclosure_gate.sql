CREATE TABLE analytics.disclosure_policy_head (
    policy_key VARCHAR(64) PRIMARY KEY,
    organization_issuer VARCHAR(256) NOT NULL,
    organization_reference_type VARCHAR(64) NOT NULL CHECK (organization_reference_type = 'Organization'),
    organization_id UUID NOT NULL,
    conservative_lineage_id UUID NOT NULL,
    active_version INTEGER NOT NULL CHECK (active_version > 0),
    history_checkpoint BIGINT NOT NULL DEFAULT 0 CHECK (history_checkpoint >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE analytics.disclosure_lineage (
    lineage_id UUID PRIMARY KEY,
    policy_key VARCHAR(64) NOT NULL REFERENCES analytics.disclosure_policy_head (policy_key) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (policy_key, lineage_id)
);

CREATE TABLE analytics.disclosure_policy_version (
    policy_key VARCHAR(64) NOT NULL REFERENCES analytics.disclosure_policy_head (policy_key) ON DELETE RESTRICT,
    policy_version INTEGER NOT NULL CHECK (policy_version > 0),
    minimum_cell_count BIGINT NOT NULL CHECK (minimum_cell_count > 0),
    external_baseline_complete BOOLEAN NOT NULL,
    conservative_lineage_id UUID NOT NULL,
    requested_fingerprint CHAR(64) NOT NULL CHECK (requested_fingerprint ~ '^[0-9a-f]{64}$'),
    effective_fingerprint CHAR(64) NOT NULL CHECK (effective_fingerprint ~ '^[0-9a-f]{64}$'),
    activated_at TIMESTAMPTZ NOT NULL,
    audit_record_id UUID,
    PRIMARY KEY (policy_key, policy_version),
    FOREIGN KEY (policy_key, conservative_lineage_id)
        REFERENCES analytics.disclosure_lineage (policy_key, lineage_id)
        ON DELETE RESTRICT
);

CREATE TABLE analytics.disclosure_policy_output_lineage (
    policy_key VARCHAR(64) NOT NULL,
    policy_version INTEGER NOT NULL,
    output_key VARCHAR(128) NOT NULL,
    lineage_id UUID NOT NULL,
    PRIMARY KEY (policy_key, policy_version, output_key, lineage_id),
    FOREIGN KEY (policy_key, policy_version)
        REFERENCES analytics.disclosure_policy_version (policy_key, policy_version)
        ON DELETE RESTRICT,
    FOREIGN KEY (policy_key, lineage_id)
        REFERENCES analytics.disclosure_lineage (policy_key, lineage_id)
        ON DELETE RESTRICT
);

CREATE INDEX analytics_disclosure_policy_output_lookup_idx
    ON analytics.disclosure_policy_output_lineage (policy_key, policy_version, output_key);

CREATE TABLE analytics.disclosure_reservation (
    reservation_id UUID PRIMARY KEY,
    policy_key VARCHAR(64) NOT NULL,
    policy_version INTEGER NOT NULL,
    operation_id UUID NOT NULL,
    operation_fingerprint CHAR(64) NOT NULL CHECK (operation_fingerprint ~ '^[0-9a-f]{64}$'),
    state VARCHAR(24) NOT NULL CHECK (
        state IN ('RESERVED', 'DISCLOSED', 'POTENTIALLY_DISCLOSED', 'WITHDRAWN_NOT_SENT', 'WITHHELD', 'REVIEW_REQUIRED')
    ),
    reason_code VARCHAR(40) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    origin VARCHAR(24) NOT NULL CHECK (origin IN ('LOCAL_RELEASE', 'KNOWN_EXTERNAL_BASELINE')),
    history_checkpoint BIGINT NOT NULL CHECK (history_checkpoint >= 0),
    audit_record_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (policy_key, operation_id),
    FOREIGN KEY (policy_key, policy_version)
        REFERENCES analytics.disclosure_policy_version (policy_key, policy_version)
        ON DELETE RESTRICT,
    CONSTRAINT analytics_disclosure_reservation_origin_state CHECK (
        origin <> 'KNOWN_EXTERNAL_BASELINE' OR state = 'DISCLOSED'
    )
);

CREATE INDEX analytics_disclosure_reservation_active_idx
    ON analytics.disclosure_reservation (policy_key, state, history_checkpoint);

CREATE TABLE analytics.disclosure_reservation_lineage (
    reservation_id UUID NOT NULL REFERENCES analytics.disclosure_reservation (reservation_id) ON DELETE RESTRICT,
    lineage_id UUID NOT NULL REFERENCES analytics.disclosure_lineage (lineage_id) ON DELETE RESTRICT,
    PRIMARY KEY (reservation_id, lineage_id)
);

CREATE INDEX analytics_disclosure_reservation_lineage_lookup_idx
    ON analytics.disclosure_reservation_lineage (lineage_id, reservation_id);

CREATE TABLE analytics.disclosure_reservation_output (
    reservation_id UUID NOT NULL REFERENCES analytics.disclosure_reservation (reservation_id) ON DELETE RESTRICT,
    output_key VARCHAR(128) NOT NULL,
    output_value BIGINT NOT NULL CHECK (output_value >= 0),
    PRIMARY KEY (reservation_id, output_key)
);

CREATE TABLE analytics.disclosure_reservation_term (
    reservation_id UUID NOT NULL,
    output_key VARCHAR(128) NOT NULL,
    cell_key VARCHAR(128) NOT NULL,
    coefficient BIGINT NOT NULL CHECK (coefficient > 0 AND coefficient <= 1000000),
    PRIMARY KEY (reservation_id, output_key, cell_key),
    FOREIGN KEY (reservation_id, output_key)
        REFERENCES analytics.disclosure_reservation_output (reservation_id, output_key)
        ON DELETE RESTRICT
);

CREATE TABLE analytics.disclosure_reservation_cell (
    reservation_id UUID NOT NULL REFERENCES analytics.disclosure_reservation (reservation_id) ON DELETE RESTRICT,
    cell_key VARCHAR(128) NOT NULL,
    cell_value BIGINT NOT NULL CHECK (cell_value >= 0),
    PRIMARY KEY (reservation_id, cell_key)
);
