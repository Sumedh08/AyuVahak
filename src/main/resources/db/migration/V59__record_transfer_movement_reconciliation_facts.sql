ALTER TABLE transfer.progress_fact
    DROP CONSTRAINT progress_fact_fact_kind_check,
    ADD CONSTRAINT progress_fact_fact_kind_check CHECK (
        fact_kind IN (
            'DEPARTURE', 'INTERRUPTION', 'RESUMPTION', 'ARRIVAL',
            'MOVEMENT_RECONCILIATION', 'HANDOVER', 'COMPLETION', 'CANCELLATION',
            'FINAL_ABANDONMENT', 'CANCELLATION_RELEASE', 'REFERRAL_CANCELLATION',
            'CAPACITY_EXCEPTION', 'CAPACITY_RECONFIRMATION'
        )
    ),
    DROP CONSTRAINT transfer_progress_kind_status,
    ADD CONSTRAINT transfer_progress_kind_status CHECK (
        (fact_kind = 'DEPARTURE' AND previous_status IN ('READY', 'CANCELLATION_PENDING_RELEASE')
            AND transfer_status = 'IN_TRANSIT'
            AND authority_code = 'TransferProgressAuthority') OR
        (fact_kind = 'INTERRUPTION' AND previous_status = 'IN_TRANSIT'
            AND transfer_status = 'INTERRUPTED'
            AND authority_code = 'TransferProgressAuthority' AND reason_code IS NOT NULL) OR
        (fact_kind = 'RESUMPTION' AND previous_status = 'INTERRUPTED'
            AND transfer_status = 'IN_TRANSIT'
            AND authority_code = 'TransferProgressAuthority') OR
        (fact_kind = 'ARRIVAL' AND previous_status IN (
            'AWAITING_RESERVATION', 'READY', 'COORDINATION_EXCEPTION', 'IN_TRANSIT', 'INTERRUPTED'
        ) AND transfer_status = 'ARRIVED' AND authority_code = 'TransferProgressAuthority') OR
        (fact_kind = 'MOVEMENT_RECONCILIATION' AND previous_status IS NOT NULL
            AND previous_status = transfer_status
            AND authority_code = 'TransferProgressAuthority'
            AND exception_code = 'MOVEMENT_EVIDENCE_REQUIRES_RECONCILIATION'
            AND reason_code IS NOT NULL) OR
        (fact_kind = 'HANDOVER' AND previous_status = 'ARRIVED' AND transfer_status = 'HANDED_OVER'
            AND authority_code = 'TransferHandoverAuthority' AND exception_code IS NULL) OR
        (fact_kind = 'COMPLETION' AND previous_status = 'HANDED_OVER' AND transfer_status = 'COMPLETED'
            AND authority_code = 'TransferCompletionAuthority' AND reason_code IS NOT NULL
            AND exception_code IS NULL) OR
        (fact_kind = 'CANCELLATION' AND previous_status IN (
            'AWAITING_RESERVATION', 'READY', 'COORDINATION_EXCEPTION'
        ) AND transfer_status = 'CANCELLATION_PENDING_RELEASE'
            AND authority_code = 'TransferProgressAuthority' AND reason_code IS NOT NULL) OR
        (fact_kind = 'FINAL_ABANDONMENT' AND previous_status = 'INTERRUPTED'
            AND transfer_status = 'CANCELLATION_PENDING_RELEASE'
            AND authority_code = 'TransferProgressAuthority' AND reason_code IS NOT NULL) OR
        (fact_kind = 'CANCELLATION_RELEASE' AND previous_status = 'CANCELLATION_PENDING_RELEASE'
            AND transfer_status = 'CANCELLED'
            AND authority_code = 'TransferReservationReconciliation') OR
        (fact_kind = 'REFERRAL_CANCELLATION' AND previous_status IN (
            'AWAITING_RESERVATION', 'READY', 'COORDINATION_EXCEPTION'
        ) AND transfer_status = 'CANCELLATION_PENDING_RELEASE'
            AND authority_code = 'TransferReservationReconciliation' AND reason_code IS NOT NULL) OR
        (fact_kind = 'CAPACITY_EXCEPTION' AND previous_status IN (
            'AWAITING_RESERVATION', 'READY', 'COORDINATION_EXCEPTION'
        ) AND transfer_status = 'COORDINATION_EXCEPTION'
            AND authority_code = 'TransferReservationReconciliation' AND reason_code IS NOT NULL) OR
        (fact_kind = 'CAPACITY_RECONFIRMATION' AND previous_status = 'COORDINATION_EXCEPTION'
            AND transfer_status = 'READY' AND authority_code = 'AcceptanceAuthority'
            AND reason_code IS NOT NULL AND exception_code IS NULL)
    );

ALTER TABLE transfer.progress_fact
    DROP CONSTRAINT transfer_progress_exception_shape,
    ADD CONSTRAINT transfer_progress_exception_shape CHECK (
        fact_kind IN ('ARRIVAL', 'MOVEMENT_RECONCILIATION') OR exception_code IS NULL
    );

ALTER TABLE transfer.progress_fact
    DROP CONSTRAINT transfer_progress_previous_status_check,
    ADD CONSTRAINT transfer_progress_previous_status_check CHECK (
        previous_status IS NULL OR previous_status IN (
            'AWAITING_RESERVATION', 'CANCELLATION_PENDING_RELEASE', 'READY',
            'COORDINATION_EXCEPTION', 'IN_TRANSIT', 'INTERRUPTED', 'ARRIVED',
            'HANDED_OVER', 'COMPLETED', 'CANCELLED'
        )
    );

COMMENT ON CONSTRAINT transfer_progress_kind_status ON transfer.progress_fact IS
    'Allows immutable movement chronology exceptions to remain visible without rewriting the recorded Transfer status.';
