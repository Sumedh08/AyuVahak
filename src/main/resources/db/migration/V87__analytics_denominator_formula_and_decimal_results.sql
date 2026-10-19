ALTER TABLE analytics.indicator_definition_version
    ALTER COLUMN aggregation_formula TYPE VARCHAR(24),
    DROP CONSTRAINT IF EXISTS indicator_definition_version_aggregation_formula_check,
    ADD CONSTRAINT analytics_indicator_formula_supported CHECK (
        aggregation_formula IN ('SUM', 'RATIO_6DP_HALF_EVEN')
    ),
    DROP CONSTRAINT IF EXISTS indicator_definition_version_denominator_definition_check,
    ADD CONSTRAINT analytics_indicator_denominator_supported CHECK (
        denominator_definition IN ('NONE', 'SUM_OF_MEASURES')
    );

ALTER TABLE analytics.indicator_definition_measure
    ADD COLUMN measure_role VARCHAR(12) NOT NULL DEFAULT 'NUMERATOR'
        CHECK (measure_role IN ('NUMERATOR', 'DENOMINATOR'));

ALTER TABLE analytics.aggregate_snapshot
    ADD COLUMN aggregate_decimal_value NUMERIC(20, 6)
        CHECK (aggregate_decimal_value IS NULL OR aggregate_decimal_value >= 0),
    DROP CONSTRAINT IF EXISTS analytics_aggregate_completeness_value,
    ADD CONSTRAINT analytics_aggregate_completeness_value CHECK (
        (completeness = 'COMPLETE'
            AND ((aggregate_value IS NOT NULL AND aggregate_decimal_value IS NULL)
                OR (aggregate_value IS NULL AND aggregate_decimal_value IS NOT NULL)))
        OR (completeness <> 'COMPLETE'
            AND aggregate_value IS NULL AND aggregate_decimal_value IS NULL)
    );
