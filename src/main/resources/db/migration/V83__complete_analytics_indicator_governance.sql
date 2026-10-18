ALTER TABLE analytics.indicator_definition_version
    ADD COLUMN owner_approved_purpose VARCHAR(512) NOT NULL DEFAULT 'Synthetic legacy indicator definition',
    ADD COLUMN effective_from TIMESTAMPTZ NOT NULL DEFAULT TIMESTAMPTZ '1970-01-01 00:00:00+00',
    ADD COLUMN effective_until TIMESTAMPTZ,
    ADD COLUMN reporting_calendar VARCHAR(24) NOT NULL DEFAULT 'CUSTOM_HALF_OPEN'
        CHECK (reporting_calendar IN ('CUSTOM_HALF_OPEN', 'DAILY', 'WEEKLY_ISO', 'MONTHLY', 'QUARTERLY')),
    ADD COLUMN completeness_requirement VARCHAR(64) NOT NULL
        DEFAULT 'ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE'
        CHECK (completeness_requirement = 'ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE'),
    ADD COLUMN maximum_source_age_seconds BIGINT
        CHECK (maximum_source_age_seconds IS NULL OR maximum_source_age_seconds > 0),
    ADD COLUMN sensitivity_classification VARCHAR(32) NOT NULL DEFAULT 'SENSITIVE_AGGREGATE'
        CHECK (sensitivity_classification IN ('SENSITIVE_AGGREGATE', 'LOW_SENSITIVITY_AGGREGATE')),
    ADD COLUMN required_disclosure_policy_key VARCHAR(64),
    ADD COLUMN authority_identifier VARCHAR(128),
    ADD COLUMN authority_definition_version VARCHAR(64),
    ADD COLUMN denominator_definition VARCHAR(16) NOT NULL DEFAULT 'NONE'
        CHECK (denominator_definition = 'NONE'),
    ADD CONSTRAINT analytics_indicator_definition_effective_interval CHECK (
        effective_until IS NULL OR effective_from < effective_until
    ),
    ADD CONSTRAINT analytics_indicator_definition_authority_pair CHECK (
        (authority_identifier IS NULL AND authority_definition_version IS NULL)
        OR (authority_identifier IS NOT NULL AND authority_definition_version IS NOT NULL)
    ),
    ADD CONSTRAINT analytics_indicator_definition_policy_key CHECK (
        required_disclosure_policy_key IS NULL
        OR required_disclosure_policy_key ~ '^[A-Z0-9][A-Z0-9._:-]{0,63}$'
    );

UPDATE analytics.indicator_definition_version
SET owner_approved_purpose = description;

ALTER TABLE analytics.indicator_definition_version
    ALTER COLUMN owner_approved_purpose DROP DEFAULT;

CREATE TABLE analytics.indicator_definition_dimension (
    indicator_key VARCHAR(64) NOT NULL,
    definition_version INTEGER NOT NULL,
    dimension_key VARCHAR(32) NOT NULL
        CHECK (dimension_key IN ('ORGANIZATION', 'SOURCE_ATTRIBUTION')),
    PRIMARY KEY (indicator_key, definition_version, dimension_key),
    FOREIGN KEY (indicator_key, definition_version)
        REFERENCES analytics.indicator_definition_version (indicator_key, definition_version)
        ON DELETE RESTRICT
);

INSERT INTO analytics.indicator_definition_dimension (indicator_key, definition_version, dimension_key)
SELECT indicator_key, definition_version, 'ORGANIZATION'
FROM analytics.indicator_definition_version;

INSERT INTO analytics.indicator_definition_dimension (indicator_key, definition_version, dimension_key)
SELECT DISTINCT indicator_key, definition_version, 'SOURCE_ATTRIBUTION'
FROM analytics.indicator_definition_measure
WHERE attribution IN ('ORIGIN', 'DESTINATION');
