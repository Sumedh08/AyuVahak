ALTER TABLE transport.status_observation
    DROP CONSTRAINT status_observation_status_code_check;

ALTER TABLE transport.status_observation
    ADD CONSTRAINT status_observation_status_code_check
    CHECK (status_code IN (
        'AWAITING_ASSIGNMENT', 'EN_ROUTE_TO_ORIGIN', 'AT_ORIGIN', 'PATIENT_ONBOARD',
        'EN_ROUTE_TO_DESTINATION', 'ARRIVED_DESTINATION', 'INTERRUPTED', 'UNFULFILLED'
    ));
