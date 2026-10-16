ALTER TABLE capacity.reservation_operation
    DROP CONSTRAINT reservation_operation_transfer_id_key;

ALTER TABLE capacity.coordination_reservation
    DROP CONSTRAINT coordination_reservation_transfer_id_key;

CREATE INDEX capacity_reservation_operation_transfer_idx
    ON capacity.reservation_operation (transfer_id, operation_id);

CREATE INDEX capacity_coordination_reservation_transfer_idx
    ON capacity.coordination_reservation (transfer_id, reservation_status, operation_id);

CREATE TABLE capacity.reservation_release_operation (
    release_operation_id uuid PRIMARY KEY,
    reservation_operation_id uuid NOT NULL
        REFERENCES capacity.reservation_operation (operation_id) ON DELETE RESTRICT,
    transfer_id uuid NOT NULL,
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    outcome varchar(16) CHECK (outcome IS NULL OR outcome IN ('RELEASED', 'EXPIRED', 'NOT_RESERVED')),
    reservation_id uuid REFERENCES capacity.coordination_reservation (reservation_id) ON DELETE RESTRICT,
    lifecycle_sequence bigint CHECK (lifecycle_sequence IS NULL OR lifecycle_sequence > 0),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT capacity_reservation_release_result_shape CHECK (
        (outcome IS NULL AND reservation_id IS NULL AND lifecycle_sequence IS NULL)
        OR (outcome IN ('RELEASED', 'EXPIRED') AND reservation_id IS NOT NULL AND lifecycle_sequence IS NOT NULL)
        OR (outcome = 'NOT_RESERVED' AND reservation_id IS NULL AND lifecycle_sequence IS NULL)
    )
);

CREATE INDEX capacity_reservation_release_transfer_idx
    ON capacity.reservation_release_operation (transfer_id, recorded_at);
