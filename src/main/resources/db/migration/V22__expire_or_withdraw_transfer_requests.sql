ALTER TABLE transfer.request_attempt
    DROP CONSTRAINT transfer_request_status_check,
    ADD CONSTRAINT transfer_request_status_check
        CHECK (request_status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'WITHDRAWN'));

CREATE INDEX transfer_request_due_expiry_idx
    ON transfer.request_attempt (expires_at, request_attempt_id)
    WHERE request_status = 'PENDING';
