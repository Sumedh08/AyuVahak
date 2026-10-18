CREATE SEQUENCE capacity.capacity_observation_source_sequence;

ALTER TABLE capacity.capacity_observation
    ADD COLUMN source_sequence bigint;

WITH ordered_observations AS (
    SELECT observation_id,
           row_number() OVER (ORDER BY received_at, observed_at, observation_id) AS source_sequence
      FROM capacity.capacity_observation
)
UPDATE capacity.capacity_observation observation
   SET source_sequence = ordered_observations.source_sequence
  FROM ordered_observations
 WHERE ordered_observations.observation_id = observation.observation_id;

SELECT setval(
    'capacity.capacity_observation_source_sequence',
    GREATEST(COALESCE((SELECT max(source_sequence) FROM capacity.capacity_observation), 0), 1),
    true
);

ALTER TABLE capacity.capacity_observation
    ALTER COLUMN source_sequence SET DEFAULT nextval('capacity.capacity_observation_source_sequence'),
    ALTER COLUMN source_sequence SET NOT NULL,
    ADD CONSTRAINT capacity_observation_source_sequence_positive CHECK (source_sequence > 0);

CREATE UNIQUE INDEX capacity_observation_source_sequence_uq
    ON capacity.capacity_observation (source_sequence);
