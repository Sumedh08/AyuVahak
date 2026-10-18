ALTER TABLE transfer.federated_origin_receiving_attempt
  ADD COLUMN remote_reservation_status VARCHAR(32),
  ADD COLUMN remote_clinical_assessment_outcome VARCHAR(32),
  ADD COLUMN remote_clinical_assessment_reason_code VARCHAR(64),
  ADD COLUMN remote_clinical_assessment_occurred_at TIMESTAMPTZ,
  ADD COLUMN remote_clinical_assessment_recorded_at TIMESTAMPTZ,
  ADD COLUMN remote_operational_assessment_outcome VARCHAR(32),
  ADD COLUMN remote_operational_assessment_reason_code VARCHAR(64),
  ADD COLUMN remote_operational_assessment_occurred_at TIMESTAMPTZ,
  ADD COLUMN remote_operational_assessment_recorded_at TIMESTAMPTZ,
  ADD COLUMN remote_accepted_at TIMESTAMPTZ,
  ADD COLUMN remote_source_updated_at TIMESTAMPTZ;

UPDATE transfer.federated_origin_receiving_attempt
SET remote_reservation_status = CASE remote_outcome
      WHEN 'RECEIVED' THEN 'NOT_REQUESTED'
      WHEN 'REJECTED' THEN 'NOT_REQUESTED'
      WHEN 'ACCEPTED_RESERVED' THEN 'RESERVED'
      WHEN 'ACCEPTED_UNRESERVED' THEN 'UNKNOWN'
      ELSE NULL
    END,
    operation_state = CASE
      WHEN remote_outcome = 'ACCEPTED_UNRESERVED' AND operation_state = 'RESOLVED'
        THEN 'OUTCOME_UNKNOWN'
      ELSE operation_state
    END;

ALTER TABLE transfer.federated_origin_receiving_attempt
  ADD CONSTRAINT federated_origin_remote_reservation_status_check
    CHECK (remote_reservation_status IS NULL OR remote_reservation_status IN (
      'NOT_REQUESTED', 'PENDING', 'OUTCOME_UNKNOWN', 'RESERVED', 'CONFLICT', 'UNKNOWN',
      'INVALID', 'UNAVAILABLE', 'AT_RISK', 'RELEASED', 'EXPIRED')),
  ADD CONSTRAINT federated_origin_remote_reservation_result_consistent
    CHECK ((remote_outcome IS NULL) = (remote_reservation_status IS NULL)),
  ADD CONSTRAINT federated_origin_remote_clinical_assessment_complete
    CHECK (num_nonnulls(remote_clinical_assessment_outcome,
                        remote_clinical_assessment_reason_code,
                        remote_clinical_assessment_occurred_at,
                        remote_clinical_assessment_recorded_at) = 0
        OR (num_nonnulls(remote_clinical_assessment_outcome,
                         remote_clinical_assessment_reason_code,
                         remote_clinical_assessment_occurred_at,
                         remote_clinical_assessment_recorded_at) = 4
            AND remote_clinical_assessment_outcome IN ('SUPPORTS', 'DOES_NOT_SUPPORT', 'INDETERMINATE')
            AND remote_clinical_assessment_reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'
            AND remote_clinical_assessment_occurred_at IS NOT NULL
            AND remote_clinical_assessment_recorded_at IS NOT NULL)),
  ADD CONSTRAINT federated_origin_remote_operational_assessment_complete
    CHECK (num_nonnulls(remote_operational_assessment_outcome,
                        remote_operational_assessment_reason_code,
                        remote_operational_assessment_occurred_at,
                        remote_operational_assessment_recorded_at) = 0
        OR (num_nonnulls(remote_operational_assessment_outcome,
                         remote_operational_assessment_reason_code,
                         remote_operational_assessment_occurred_at,
                         remote_operational_assessment_recorded_at) = 4
            AND remote_operational_assessment_outcome IN ('SUPPORTS', 'DOES_NOT_SUPPORT', 'INDETERMINATE')
            AND remote_operational_assessment_reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'
            AND remote_operational_assessment_occurred_at IS NOT NULL
            AND remote_operational_assessment_recorded_at IS NOT NULL));
