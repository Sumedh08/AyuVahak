ALTER TABLE transfer.request_attempt
    ADD COLUMN terminal_occurred_at timestamptz,
    ADD COLUMN terminal_recorded_at timestamptz,
    ADD CONSTRAINT transfer_request_terminal_time_shape CHECK (
        (
            request_status IN ('EXPIRED', 'WITHDRAWN')
            AND terminal_occurred_at IS NOT NULL
            AND terminal_recorded_at IS NOT NULL
        )
        OR
        (
            request_status NOT IN ('EXPIRED', 'WITHDRAWN')
            AND terminal_occurred_at IS NULL
            AND terminal_recorded_at IS NULL
        )
    );
