ALTER TABLE analytics.disclosure_reservation_output
    ADD COLUMN equation_result BIGINT,
    ADD COLUMN applies_minimum_cell_count BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE analytics.disclosure_reservation_output
SET equation_result = output_value;

ALTER TABLE analytics.disclosure_reservation_output
    ALTER COLUMN equation_result SET NOT NULL;

DO $$
DECLARE
    constraint_row RECORD;
BEGIN
    FOR constraint_row IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'analytics.disclosure_reservation_term'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%coefficient > 0%'
    LOOP
        EXECUTE format(
            'ALTER TABLE analytics.disclosure_reservation_term DROP CONSTRAINT %I',
            constraint_row.conname
        );
    END LOOP;
END;
$$;

ALTER TABLE analytics.disclosure_reservation_term
    ADD CONSTRAINT analytics_disclosure_reservation_term_signed_coefficient
        CHECK (coefficient <> 0 AND coefficient BETWEEN -1000000 AND 1000000);
