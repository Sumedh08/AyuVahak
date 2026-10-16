ALTER TABLE transport.provider_submission
    DROP CONSTRAINT provider_submission_submission_state_check,
    ADD CONSTRAINT provider_submission_submission_state_check CHECK (
        submission_state IN (
            'ATTEMPTING', 'RETRY_PENDING', 'ACKNOWLEDGED', 'OUTCOME_UNKNOWN', 'REJECTED', 'REVOKED'
        )
    ),
    DROP CONSTRAINT transport_provider_submission_state_shape,
    ADD CONSTRAINT transport_provider_submission_state_shape CHECK (
        (submission_state = 'ACKNOWLEDGED' AND response_status BETWEEN 200 AND 299 AND failure_code IS NULL)
        OR (submission_state IN ('RETRY_PENDING', 'OUTCOME_UNKNOWN', 'REJECTED', 'REVOKED')
            AND response_status IS NULL AND failure_code IS NOT NULL)
        OR (submission_state = 'ATTEMPTING' AND response_status IS NULL AND failure_code IS NULL)
    ),
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 1
        CHECK (attempt_count BETWEEN 1 AND 3);
