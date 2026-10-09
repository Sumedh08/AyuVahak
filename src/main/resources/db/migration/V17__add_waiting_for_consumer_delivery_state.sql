ALTER TABLE platform_eventing.outbox_delivery
    DROP CONSTRAINT outbox_delivery_state_valid;

ALTER TABLE platform_eventing.outbox_delivery
    ALTER COLUMN state TYPE varchar(32);

ALTER TABLE platform_eventing.outbox_delivery
    ADD CONSTRAINT outbox_delivery_state_valid
        CHECK (state IN ('PENDING', 'LEASED', 'DELIVERED', 'STALLED', 'UNHANDLED', 'WAITING_FOR_CONSUMER'));

ALTER TABLE platform_eventing.outbox_delivery
    ADD CONSTRAINT outbox_delivery_waiting_shape
        CHECK (
            state <> 'WAITING_FOR_CONSUMER'
            OR (
                attempt_count = 0
                AND lease_generation = 0
                AND lease_until IS NULL
                AND last_failure_category IS NULL
            )
        );
