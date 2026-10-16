CREATE TABLE transport.provider_submission (
    operation_reference uuid NOT NULL,
    transport_request_id uuid NOT NULL
        REFERENCES transport.transport_request (transport_request_id) ON DELETE RESTRICT,
    operation_kind varchar(32) NOT NULL CHECK (
        operation_kind IN ('DISPATCH', 'CANCELLATION', 'DESTINATION_CHANGE')
    ),
    partner_key varchar(64) NOT NULL CHECK (
        partner_key ~ '^[A-Z0-9][A-Z0-9._-]{0,63}$'
    ),
    submission_state varchar(32) NOT NULL CHECK (
        submission_state IN ('ATTEMPTING', 'ACKNOWLEDGED', 'OUTCOME_UNKNOWN', 'REJECTED', 'REVOKED')
    ),
    configuration_version integer CHECK (configuration_version IS NULL OR configuration_version > 0),
    response_status integer CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    failure_code varchar(64) CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    PRIMARY KEY (operation_kind, operation_reference),
    CONSTRAINT transport_provider_submission_state_shape CHECK (
        (submission_state = 'ACKNOWLEDGED' AND response_status BETWEEN 200 AND 299 AND failure_code IS NULL)
        OR (submission_state IN ('OUTCOME_UNKNOWN', 'REJECTED', 'REVOKED')
            AND response_status IS NULL AND failure_code IS NOT NULL)
        OR (submission_state = 'ATTEMPTING' AND response_status IS NULL AND failure_code IS NULL)
    ),
    CONSTRAINT transport_provider_submission_time_order CHECK (updated_at >= created_at)
);

CREATE INDEX transport_provider_submission_request_idx
    ON transport.provider_submission (transport_request_id, created_at DESC);
