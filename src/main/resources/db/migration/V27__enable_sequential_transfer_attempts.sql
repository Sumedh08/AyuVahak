ALTER TABLE transfer.transfer_record
    DROP CONSTRAINT transfer_record_transfer_status_check,
    ADD CONSTRAINT transfer_record_transfer_status_check
        CHECK (transfer_status IN (
            'AWAITING_RESERVATION', 'CANCELLATION_PENDING_RELEASE', 'READY',
            'COORDINATION_EXCEPTION', 'IN_TRANSIT', 'INTERRUPTED', 'ARRIVED',
            'HANDED_OVER', 'COMPLETED', 'CANCELLED'
        ));

ALTER TABLE transfer.progress_fact
    DROP CONSTRAINT progress_fact_previous_status_check,
    ADD CONSTRAINT transfer_progress_previous_status_check
        CHECK (previous_status IS NULL OR previous_status IN (
            'AWAITING_RESERVATION', 'CANCELLATION_PENDING_RELEASE', 'READY',
            'COORDINATION_EXCEPTION', 'IN_TRANSIT', 'INTERRUPTED', 'ARRIVED', 'HANDED_OVER'
        )),
    DROP CONSTRAINT transfer_progress_kind_status,
    ADD CONSTRAINT transfer_progress_kind_status CHECK (
        (fact_kind = 'DEPARTURE' AND previous_status = 'READY' AND transfer_status = 'IN_TRANSIT'
            AND authority_code = 'TransferProgressAuthority') OR
        (fact_kind = 'INTERRUPTION' AND previous_status = 'IN_TRANSIT' AND transfer_status = 'INTERRUPTED'
            AND authority_code = 'TransferProgressAuthority' AND reason_code IS NOT NULL) OR
        (fact_kind = 'RESUMPTION' AND previous_status = 'INTERRUPTED' AND transfer_status = 'IN_TRANSIT'
            AND authority_code = 'TransferProgressAuthority') OR
        (fact_kind = 'ARRIVAL' AND previous_status IN (
            'AWAITING_RESERVATION', 'READY', 'COORDINATION_EXCEPTION', 'IN_TRANSIT', 'INTERRUPTED'
        ) AND transfer_status = 'ARRIVED' AND authority_code = 'TransferProgressAuthority') OR
        (fact_kind = 'HANDOVER' AND previous_status = 'ARRIVED' AND transfer_status = 'HANDED_OVER'
            AND authority_code = 'TransferHandoverAuthority' AND exception_code IS NULL) OR
        (fact_kind = 'COMPLETION' AND previous_status = 'HANDED_OVER' AND transfer_status = 'COMPLETED'
            AND authority_code = 'TransferCompletionAuthority' AND reason_code IS NOT NULL
            AND exception_code IS NULL)
    );

CREATE UNIQUE INDEX transfer_one_pending_attempt_per_journey_idx
    ON transfer.request_attempt (journey_id)
    WHERE request_status = 'PENDING';

ALTER TABLE transfer.request_attempt
    DROP CONSTRAINT transfer_first_attempt_number;
