ALTER TABLE transport.status_observation
    ADD COLUMN destination_facility_id uuid;

ALTER TABLE transport.status_observation
    ADD COLUMN destination_chronology_resolved boolean NOT NULL DEFAULT true;

-- Copy the narrow destination fact from historical Transport arrival envelopes so upgrades retain
-- chronology before the evidence column existed. Runtime Transport queries remain owner-local.
WITH matched_arrival_events AS (
    SELECT
        observation.status_observation_id,
        CASE
            WHEN event.envelope #>> '{payload,destinationFacilityReference}'
                 ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            THEN (event.envelope #>> '{payload,destinationFacilityReference}')::uuid
            ELSE NULL
        END AS destination_facility_id,
        count(*) OVER (PARTITION BY observation.status_observation_id) AS matching_event_count,
        row_number() OVER (
            PARTITION BY observation.status_observation_id
            ORDER BY event.event_id
        ) AS event_number
    FROM transport.status_observation AS observation
    JOIN transport.transport_request AS request
      ON request.transport_request_id = observation.transport_request_id
    JOIN platform_eventing.internal_event AS event
      ON event.producer_context = 'transport'
     AND event.event_type = 'transport.arrival-reported.v1'
     AND event.aggregate_ref = 'transport-request/' || request.transport_request_id::text
     AND event.envelope #>> '{payload,evidenceReference}' = observation.status_observation_id::text
      AND event.envelope #>> '{payload,transportRequestReference}' = request.transport_request_id::text
    WHERE observation.status_code = 'ARRIVED_DESTINATION'
      AND observation.observation_outcome IN ('APPLIED', 'RECONCILIATION_REQUIRED')
)
UPDATE transport.status_observation AS observation
SET destination_facility_id = matched.destination_facility_id,
    destination_chronology_resolved = true
FROM matched_arrival_events AS matched
WHERE matched.status_observation_id = observation.status_observation_id
  AND matched.matching_event_count = 1
  AND matched.event_number = 1
  AND matched.destination_facility_id IS NOT NULL;

-- Missing or duplicate historical envelopes are explicit ambiguity, not evidence of no arrival.
UPDATE transport.status_observation
SET destination_chronology_resolved = false
WHERE status_code = 'ARRIVED_DESTINATION'
  AND observation_outcome IN ('APPLIED', 'RECONCILIATION_REQUIRED')
  AND destination_facility_id IS NULL;

ALTER TABLE transport.status_observation
    ADD CONSTRAINT transport_arrival_destination_chronology_resolved
    CHECK (
        status_code <> 'ARRIVED_DESTINATION'
        OR observation_outcome NOT IN ('APPLIED', 'RECONCILIATION_REQUIRED')
        OR (destination_chronology_resolved AND destination_facility_id IS NOT NULL)
        OR (NOT destination_chronology_resolved AND destination_facility_id IS NULL)
    );

COMMENT ON COLUMN transport.status_observation.destination_facility_id IS
    'Opaque Directory Facility reference effective when source evidence occurred; retained by Transport so chronology queries do not depend on platform event-store retention.';

COMMENT ON COLUMN transport.status_observation.destination_chronology_resolved IS
    'False means a historical applicable arrival exists but its destination cannot be recovered unambiguously; destination-change acknowledgement checks fail closed.';

CREATE INDEX transport_status_arrival_chronology
    ON transport.status_observation (transport_request_id, destination_facility_id, occurred_at)
    WHERE status_code = 'ARRIVED_DESTINATION'
      AND observation_outcome IN ('APPLIED', 'RECONCILIATION_REQUIRED');

CREATE INDEX transport_status_arrival_chronology_unresolved
    ON transport.status_observation (transport_request_id, occurred_at)
    WHERE status_code = 'ARRIVED_DESTINATION'
      AND observation_outcome IN ('APPLIED', 'RECONCILIATION_REQUIRED')
      AND destination_chronology_resolved = false;
