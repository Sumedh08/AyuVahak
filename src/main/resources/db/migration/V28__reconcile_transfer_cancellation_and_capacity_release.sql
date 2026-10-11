ALTER TABLE capacity.coordination_reservation
    ADD COLUMN reservation_status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (reservation_status IN ('ACTIVE', 'AT_RISK', 'RELEASED', 'EXPIRED')),
    ADD COLUMN lifecycle_sequence bigint NOT NULL DEFAULT 1 CHECK (lifecycle_sequence > 0),
    ADD COLUMN status_recorded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE transfer.progress_fact
    DROP CONSTRAINT progress_fact_fact_kind_check,
    ADD CONSTRAINT progress_fact_fact_kind_check CHECK (
        fact_kind IN (
            'DEPARTURE', 'INTERRUPTION', 'RESUMPTION', 'ARRIVAL', 'HANDOVER', 'COMPLETION',
            'CANCELLATION', 'CANCELLATION_RELEASE', 'REFERRAL_CANCELLATION', 'CAPACITY_EXCEPTION'
        )
    ),
    DROP CONSTRAINT transfer_progress_previous_status_check,
    ADD CONSTRAINT transfer_progress_previous_status_check
        CHECK (previous_status IS NULL OR previous_status IN (
            'AWAITING_RESERVATION', 'CANCELLATION_PENDING_RELEASE', 'READY',
            'COORDINATION_EXCEPTION', 'IN_TRANSIT', 'INTERRUPTED', 'ARRIVED', 'HANDED_OVER'
        )),
    DROP CONSTRAINT progress_fact_transfer_status_check,
    ADD CONSTRAINT progress_fact_transfer_status_check CHECK (
        transfer_status IN (
            'CANCELLATION_PENDING_RELEASE', 'COORDINATION_EXCEPTION', 'IN_TRANSIT',
            'INTERRUPTED', 'ARRIVED', 'HANDED_OVER', 'COMPLETED', 'CANCELLED'
        )
    ),
    DROP CONSTRAINT progress_fact_authority_code_check,
    ADD CONSTRAINT progress_fact_authority_code_check CHECK (
        authority_code IN (
            'TransferProgressAuthority', 'TransferHandoverAuthority',
            'TransferCompletionAuthority', 'TransferReservationReconciliation'
        )
    ),
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
            AND exception_code IS NULL) OR
        (fact_kind = 'CANCELLATION' AND previous_status IN (
            'AWAITING_RESERVATION', 'READY', 'COORDINATION_EXCEPTION'
        ) AND transfer_status = 'CANCELLATION_PENDING_RELEASE'
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
            AND authority_code = 'TransferReservationReconciliation' AND reason_code IS NOT NULL)
    );
