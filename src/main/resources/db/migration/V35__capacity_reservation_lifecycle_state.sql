-- Capacity owns reservation lifecycle state and its migration history.
ALTER TABLE capacity.coordination_reservation
    ADD COLUMN reservation_status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (reservation_status IN ('ACTIVE', 'AT_RISK', 'RELEASED', 'EXPIRED')),
    ADD COLUMN lifecycle_sequence bigint NOT NULL DEFAULT 1 CHECK (lifecycle_sequence > 0),
    ADD COLUMN status_recorded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN lifecycle_checked_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX capacity_coordination_reservation_lifecycle_due_idx
    ON capacity.coordination_reservation (lifecycle_checked_at, expires_at, reservation_id)
    WHERE reservation_status IN ('ACTIVE', 'AT_RISK');
