ALTER TABLE capacity.reservation_operation
    ADD COLUMN subject_type varchar(32) NOT NULL DEFAULT 'TRANSFER',
    ADD COLUMN subject_id uuid;

UPDATE capacity.reservation_operation
SET subject_id = transfer_id;

ALTER TABLE capacity.reservation_operation
    ALTER COLUMN transfer_id DROP NOT NULL,
    ALTER COLUMN subject_id SET NOT NULL,
    ADD CONSTRAINT capacity_reservation_operation_subject_type
        CHECK (subject_type IN ('TRANSFER', 'FEDERATED_RECEIVING_REQUEST')),
    ADD CONSTRAINT capacity_reservation_operation_subject_shape
        CHECK (
            (subject_type = 'TRANSFER' AND transfer_id IS NOT NULL AND subject_id = transfer_id)
            OR
            (subject_type = 'FEDERATED_RECEIVING_REQUEST' AND transfer_id IS NULL)
        );

CREATE UNIQUE INDEX capacity_reservation_operation_federated_request_uq
    ON capacity.reservation_operation (subject_type, subject_id)
    WHERE subject_type = 'FEDERATED_RECEIVING_REQUEST';

ALTER TABLE capacity.coordination_reservation
    ADD COLUMN subject_type varchar(32) NOT NULL DEFAULT 'TRANSFER',
    ADD COLUMN subject_id uuid;

UPDATE capacity.coordination_reservation
SET subject_id = transfer_id;

ALTER TABLE capacity.coordination_reservation
    ALTER COLUMN transfer_id DROP NOT NULL,
    ALTER COLUMN subject_id SET NOT NULL,
    ADD CONSTRAINT capacity_coordination_reservation_subject_type
        CHECK (subject_type IN ('TRANSFER', 'FEDERATED_RECEIVING_REQUEST')),
    ADD CONSTRAINT capacity_coordination_reservation_subject_shape
        CHECK (
            (subject_type = 'TRANSFER' AND transfer_id IS NOT NULL AND subject_id = transfer_id)
            OR
            (subject_type = 'FEDERATED_RECEIVING_REQUEST' AND transfer_id IS NULL)
        );

CREATE UNIQUE INDEX capacity_coordination_reservation_federated_request_uq
    ON capacity.coordination_reservation (subject_type, subject_id)
    WHERE subject_type = 'FEDERATED_RECEIVING_REQUEST';

ALTER TABLE capacity.reservation_release_operation
    ADD COLUMN subject_type varchar(32) NOT NULL DEFAULT 'TRANSFER',
    ADD COLUMN subject_id uuid;

UPDATE capacity.reservation_release_operation
SET subject_id = transfer_id;

ALTER TABLE capacity.reservation_release_operation
    ALTER COLUMN transfer_id DROP NOT NULL,
    ALTER COLUMN subject_id SET NOT NULL,
    ADD CONSTRAINT capacity_reservation_release_subject_type
        CHECK (subject_type IN ('TRANSFER', 'FEDERATED_RECEIVING_REQUEST')),
    ADD CONSTRAINT capacity_reservation_release_subject_shape
        CHECK (
            (subject_type = 'TRANSFER' AND transfer_id IS NOT NULL AND subject_id = transfer_id)
            OR
            (subject_type = 'FEDERATED_RECEIVING_REQUEST' AND transfer_id IS NULL)
        );

CREATE INDEX capacity_reservation_release_subject_idx
    ON capacity.reservation_release_operation (subject_type, subject_id, recorded_at);
