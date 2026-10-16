package org.ayuvahak.transport.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transport.application.TransportEvidenceStore;
import org.ayuvahak.transport.domain.TransportAssignmentObservation;
import org.ayuvahak.transport.domain.TransportEtaObservation;
import org.ayuvahak.transport.domain.TransportRequest;
import org.ayuvahak.transport.domain.TransportStatusObservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for append-only Transport operational evidence. */
@Repository
public class JdbcTransportEvidenceStore implements TransportEvidenceStore {

  private final JdbcTemplate jdbc;

  public JdbcTransportEvidenceStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "JDBC template");
  }

  @Override
  public Optional<EvidenceReplay> findStatus(
      InternalId requestId, String sourceNamespace, String sourceObservationId) {
    return find(
        "SELECT status_observation_id, fingerprint, observation_outcome, request_status_after, "
            + "NULL::timestamptz AS valid_until "
            + "FROM transport.status_observation WHERE transport_request_id = ? "
            + "AND source_namespace = ? AND source_observation_id = ?",
        requestId,
        sourceNamespace,
        sourceObservationId);
  }

  @Override
  public Optional<EvidenceReplay> findAssignment(
      InternalId requestId, String sourceNamespace, String sourceObservationId) {
    return find(
        "SELECT assignment_observation_id, fingerprint, observation_outcome, "
            + "request_status_after, NULL::timestamptz AS valid_until "
            + "FROM transport.assignment_observation "
            + "WHERE transport_request_id = ? AND source_namespace = ? AND source_observation_id = ?",
        requestId,
        sourceNamespace,
        sourceObservationId);
  }

  @Override
  public Optional<EvidenceReplay> findEta(
      InternalId requestId, String sourceNamespace, String sourceObservationId) {
    return find(
        "SELECT eta_observation_id, fingerprint, 'RECORDED' AS observation_outcome, "
            + "NULL::varchar AS request_status_after, valid_until "
            + "FROM transport.eta_observation WHERE transport_request_id = ? "
            + "AND source_namespace = ? AND source_observation_id = ?",
        requestId,
        sourceNamespace,
        sourceObservationId);
  }

  @Override
  public Optional<Long> latestAppliedStatusSequence(InternalId requestId, String sourceNamespace) {
    return jdbc.query(
        "SELECT MAX(source_sequence) AS source_sequence FROM transport.status_observation "
            + "WHERE transport_request_id = ? AND source_namespace = ? "
            + "AND observation_outcome = 'APPLIED' AND source_sequence IS NOT NULL",
        result -> {
          if (!result.next()) {
            return Optional.empty();
          }
          long value = result.getLong("source_sequence");
          return result.wasNull() ? Optional.empty() : Optional.of(value);
        },
        requestId.value(),
        sourceNamespace);
  }

  @Override
  public Optional<Instant> latestAppliedStatusTime(InternalId requestId, String sourceNamespace) {
    return jdbc.query(
        "SELECT MAX(occurred_at) AS occurred_at FROM transport.status_observation "
            + "WHERE transport_request_id = ? AND source_namespace = ? "
            + "AND observation_outcome = 'APPLIED'",
        result -> {
          if (!result.next()) {
            return Optional.empty();
          }
          Timestamp time = result.getTimestamp("occurred_at");
          return Optional.ofNullable(time).map(Timestamp::toInstant);
        },
        requestId.value(),
        sourceNamespace);
  }

  @Override
  public boolean hasConfirmedPatientMovement(InternalId requestId) {
    Boolean movementRecorded =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM transport.status_observation "
                + "WHERE transport_request_id = ? AND observation_outcome = 'APPLIED' "
                + "AND status_code IN ('EN_ROUTE_TO_DESTINATION', 'ARRIVED_DESTINATION'))",
            Boolean.class,
            requestId.value());
    return Boolean.TRUE.equals(movementRecorded);
  }

  @Override
  public boolean hasArrivalAtOrBefore(
      InternalId requestId, ExternalReference destinationFacility, Instant cutoff) {
    Boolean arrivalRecorded =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM transport.status_observation "
                + "WHERE transport_request_id = ? AND status_code = 'ARRIVED_DESTINATION' "
                + "AND (destination_facility_id = ? OR destination_chronology_resolved = false) "
                + "AND occurred_at <= ? "
                + "AND observation_outcome IN ('APPLIED', 'RECONCILIATION_REQUIRED'))",
            Boolean.class,
            requestId.value(),
            uuid(destinationFacility),
            Timestamp.from(cutoff));
    return Boolean.TRUE.equals(arrivalRecorded);
  }

  @Override
  public Optional<Instant> latestEtaTime(InternalId requestId, String sourceNamespace) {
    return jdbc.query(
        "SELECT MAX(observed_at) AS observed_at FROM transport.eta_observation "
            + "WHERE transport_request_id = ? AND source_namespace = ?",
        result -> {
          if (!result.next()) {
            return Optional.empty();
          }
          Timestamp time = result.getTimestamp("observed_at");
          return Optional.ofNullable(time).map(Timestamp::toInstant);
        },
        requestId.value(),
        sourceNamespace);
  }

  @Override
  public boolean updateStatus(
      InternalId requestId,
      long expectedVersion,
      TransportRequest.Status nextStatus,
      long nextVersion,
      Instant recordedAt) {
    return jdbc.update(
            "UPDATE transport.transport_request SET request_status = ?, version = ? "
                + "WHERE transport_request_id = ? AND version = ?",
            nextStatus.name(),
            nextVersion,
            requestId.value(),
            expectedVersion)
        == 1;
  }

  @Override
  public boolean insertStatus(TransportStatusObservation observation, String fingerprint) {
    return jdbc.update(
            "INSERT INTO transport.status_observation (status_observation_id, transport_request_id, "
                + "source_namespace, source_observation_id, source_sequence, status_code, occurred_at, "
                + "recorded_at, recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, "
                + "recorded_by_identifier, authority_code, organization_id, facility_id, "
                + "destination_facility_id, correlation_id, "
                + "observation_outcome, reason_code, request_status_after, resulting_request_version, fingerprint) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            observation.id().value(),
            observation.transportRequestId().value(),
            observation.sourceNamespace(),
            observation.sourceObservationId(),
            observation.sourceSequence().isPresent()
                ? observation.sourceSequence().getAsLong()
                : null,
            observation.status().name(),
            Timestamp.from(observation.occurredAt()),
            Timestamp.from(observation.recordedAt()),
            observation.recordedBy().kind().name(),
            observation.recordedBy().identity().issuer().value(),
            observation.recordedBy().identity().resourceType(),
            observation.recordedBy().identity().value(),
            observation.authorityCode(),
            uuid(observation.organization()),
            uuid(observation.facility()),
            uuid(observation.destinationFacility()),
            observation.correlationId().value(),
            observation.outcome().name(),
            observation.reasonCode().orElse(null),
            observation.requestStatusAfter().name(),
            observation.resultingRequestVersion(),
            fingerprint)
        == 1;
  }

  @Override
  public boolean insertAssignment(TransportAssignmentObservation observation, String fingerprint) {
    return jdbc.update(
            "INSERT INTO transport.assignment_observation (assignment_observation_id, transport_request_id, "
                + "source_namespace, source_observation_id, assignment_reference, occurred_at, recorded_at, "
                + "recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, recorded_by_identifier, "
                + "authority_code, organization_id, facility_id, correlation_id, observation_outcome, "
                + "request_status_after, resulting_request_version, fingerprint) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ",
            observation.id().value(),
            observation.transportRequestId().value(),
            observation.sourceNamespace(),
            observation.sourceObservationId(),
            observation.assignmentReference(),
            Timestamp.from(observation.occurredAt()),
            Timestamp.from(observation.recordedAt()),
            observation.recordedBy().kind().name(),
            observation.recordedBy().identity().issuer().value(),
            observation.recordedBy().identity().resourceType(),
            observation.recordedBy().identity().value(),
            observation.authorityCode(),
            uuid(observation.organization()),
            uuid(observation.facility()),
            observation.correlationId().value(),
            observation.outcome(),
            observation.requestStatusAfter().name(),
            observation.resultingRequestVersion(),
            fingerprint)
        == 1;
  }

  @Override
  public boolean insertEta(TransportEtaObservation observation, String fingerprint) {
    return jdbc.update(
            "INSERT INTO transport.eta_observation (eta_observation_id, transport_request_id, "
                + "source_namespace, source_observation_id, estimated_arrival_at, observed_at, recorded_at, "
                + "valid_until, recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, "
                + "recorded_by_identifier, authority_code, organization_id, facility_id, correlation_id, fingerprint) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            observation.id().value(),
            observation.transportRequestId().value(),
            observation.sourceNamespace(),
            observation.sourceObservationId(),
            Timestamp.from(observation.estimatedArrivalAt()),
            Timestamp.from(observation.observedAt()),
            Timestamp.from(observation.recordedAt()),
            Timestamp.from(observation.validUntil()),
            observation.recordedBy().kind().name(),
            observation.recordedBy().identity().issuer().value(),
            observation.recordedBy().identity().resourceType(),
            observation.recordedBy().identity().value(),
            observation.authorityCode(),
            uuid(observation.organization()),
            uuid(observation.facility()),
            observation.correlationId().value(),
            fingerprint)
        == 1;
  }

  private Optional<EvidenceReplay> find(
      String sql, InternalId requestId, String sourceNamespace, String sourceObservationId) {
    List<EvidenceReplay> found =
        jdbc.query(
            sql,
            (result, row) -> {
              Timestamp validUntil = result.getTimestamp("valid_until");
              return new EvidenceReplay(
                  InternalId.parse(result.getObject(1, UUID.class).toString()),
                  result.getString("fingerprint").trim(),
                  result.getString("observation_outcome"),
                  result.getString("request_status_after"),
                  Optional.ofNullable(validUntil).map(Timestamp::toInstant));
            },
            requestId.value(),
            sourceNamespace,
            sourceObservationId);
    return found.stream().findFirst();
  }

  private static UUID uuid(ExternalReference reference) {
    return InternalId.parse(reference.value()).value();
  }
}
