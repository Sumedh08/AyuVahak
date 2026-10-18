CREATE SCHEMA analytics;

CREATE TABLE analytics.indicator_definition_version (
    indicator_key VARCHAR(64) NOT NULL,
    definition_version INTEGER NOT NULL CHECK (definition_version > 0),
    description VARCHAR(256) NOT NULL,
    aggregation_formula VARCHAR(16) NOT NULL CHECK (aggregation_formula = 'SUM'),
    reporting_time_zone VARCHAR(64) NOT NULL,
    zero_allowed BOOLEAN NOT NULL,
    definition_fingerprint CHAR(64) NOT NULL CHECK (definition_fingerprint ~ '^[0-9a-f]{64}$'),
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (indicator_key, definition_version)
);

CREATE TABLE analytics.indicator_definition_measure (
    indicator_key VARCHAR(64) NOT NULL,
    definition_version INTEGER NOT NULL,
    measure_position SMALLINT NOT NULL CHECK (measure_position >= 0 AND measure_position < 12),
    source_owner VARCHAR(16) NOT NULL CHECK (source_owner IN ('REFERRAL', 'TRANSFER')),
    measure_key VARCHAR(32) NOT NULL,
    attribution VARCHAR(16) NOT NULL CHECK (attribution IN ('NONE', 'ORIGIN', 'DESTINATION')),
    PRIMARY KEY (indicator_key, definition_version, measure_position),
    UNIQUE (indicator_key, definition_version, source_owner, measure_key, attribution),
    FOREIGN KEY (indicator_key, definition_version)
        REFERENCES analytics.indicator_definition_version (indicator_key, definition_version)
        ON DELETE RESTRICT,
    CONSTRAINT analytics_indicator_measure_owner_shape CHECK (
        (source_owner = 'REFERRAL' AND attribution = 'NONE'
            AND measure_key IN ('SUBMITTED_COUNT', 'RESOLVED_COUNT'))
        OR
        (source_owner = 'TRANSFER' AND attribution IN ('ORIGIN', 'DESTINATION')
            AND measure_key IN ('TRANSFER_ACCEPTED', 'TRANSFER_COMPLETED', 'TRANSFER_UNFULFILLED')
            AND NOT (measure_key = 'TRANSFER_UNFULFILLED' AND attribution = 'DESTINATION'))
    )
);

CREATE TABLE analytics.aggregate_snapshot (
    snapshot_id UUID PRIMARY KEY,
    computation_request_id UUID NOT NULL UNIQUE,
    indicator_key VARCHAR(64) NOT NULL,
    definition_version INTEGER NOT NULL,
    organization_issuer VARCHAR(256) NOT NULL,
    organization_resource_type VARCHAR(64) NOT NULL CHECK (organization_resource_type = 'Organization'),
    organization_id UUID NOT NULL,
    period_start_local DATE NOT NULL,
    period_end_exclusive_local DATE NOT NULL,
    period_start_utc TIMESTAMPTZ NOT NULL,
    period_end_exclusive_utc TIMESTAMPTZ NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    time_zone_rules_version VARCHAR(32) NOT NULL,
    completeness VARCHAR(24) NOT NULL CHECK (
        completeness IN ('COMPLETE', 'PARTIAL', 'MISSING_SOURCE', 'STALE_SOURCE', 'NOT_APPLICABLE')
    ),
    aggregate_value BIGINT CHECK (aggregate_value IS NULL OR aggregate_value >= 0),
    computed_at TIMESTAMPTZ NOT NULL,
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY (indicator_key, definition_version)
        REFERENCES analytics.indicator_definition_version (indicator_key, definition_version)
        ON DELETE RESTRICT,
    CONSTRAINT analytics_aggregate_period_nonempty CHECK (
        period_start_local < period_end_exclusive_local
        AND period_start_utc < period_end_exclusive_utc
    ),
    CONSTRAINT analytics_aggregate_completeness_value CHECK (
        (completeness = 'COMPLETE' AND aggregate_value IS NOT NULL)
        OR (completeness <> 'COMPLETE' AND aggregate_value IS NULL)
    )
);

CREATE INDEX analytics_aggregate_scope_period_idx
    ON analytics.aggregate_snapshot (
        organization_id,
        indicator_key,
        definition_version,
        period_start_utc,
        period_end_exclusive_utc
    );

CREATE TABLE analytics.aggregate_snapshot_input (
    snapshot_id UUID NOT NULL REFERENCES analytics.aggregate_snapshot (snapshot_id) ON DELETE RESTRICT,
    source_owner VARCHAR(16) NOT NULL CHECK (source_owner IN ('REFERRAL', 'TRANSFER')),
    measure_key VARCHAR(32) NOT NULL,
    attribution VARCHAR(16) NOT NULL CHECK (attribution IN ('NONE', 'ORIGIN', 'DESTINATION')),
    source_snapshot_reference UUID,
    source_cutoff TIMESTAMPTZ,
    owner_version VARCHAR(96),
    completeness VARCHAR(24) NOT NULL CHECK (
        completeness IN ('COMPLETE', 'PARTIAL', 'MISSING_SOURCE', 'STALE_SOURCE', 'NOT_APPLICABLE')
    ),
    measure_value BIGINT CHECK (measure_value IS NULL OR measure_value >= 0),
    generated_at TIMESTAMPTZ,
    PRIMARY KEY (snapshot_id, source_owner, measure_key, attribution),
    CONSTRAINT analytics_input_completeness_value CHECK (
        (completeness = 'COMPLETE' AND measure_value IS NOT NULL
            AND source_snapshot_reference IS NOT NULL AND source_cutoff IS NOT NULL
            AND owner_version IS NOT NULL AND generated_at IS NOT NULL)
        OR (completeness <> 'COMPLETE' AND measure_value IS NULL)
    )
);
