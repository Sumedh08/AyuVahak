package org.ayuvahak.transfer.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.transfer.application.FederatedReceivingRequestStore;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest.Assessment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for Transfer-owned receiver-local federated cases. */
@Repository
public class JdbcFederatedReceivingRequestStore implements FederatedReceivingRequestStore {

  private static final RowMapper<FederatedReceivingRequest> ROW_MAPPER =
      JdbcFederatedReceivingRequestStore::map;

  private final JdbcTemplate jdbc;

  public JdbcFederatedReceivingRequestStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "JDBC template");
  }

  @Override
  public Claim receive(FederatedReceivingRequest request, Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO transfer.federated_receiving_request ("
                + "receiving_request_id, origin_deployment_id, request_operation_id, "
                + "request_fingerprint, origin_journey_id, origin_facility_id, "
                + "destination_organization_issuer, destination_organization_id, "
                + "destination_facility_issuer, destination_facility_id, capacity_dimension_code, "
                + "capacity_unit, capacity_quantity, capacity_interval_start, capacity_interval_end, "
                + "request_state, capacity_reservation_status, received_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', "
                + "'NOT_REQUESTED', ?, ?) ON CONFLICT (origin_deployment_id, request_operation_id) "
                + "DO NOTHING",
            request.id().value(),
            request.originDeployment().value(),
            request.requestOperationId().value(),
            request.requestFingerprint(),
            request.originJourneyId().value(),
            request.originFacilityId(),
            request.destinationOrganization().issuer().value(),
            UUID.fromString(request.destinationOrganization().value()),
            request.destinationFacility().issuer().value(),
            UUID.fromString(request.destinationFacility().value()),
            request.capacityDimensionCode(),
            request.capacityUnit(),
            request.capacityQuantity(),
            timestamp(request.capacityIntervalStart()),
            timestamp(request.capacityIntervalEnd()),
            timestamp(recordedAt),
            timestamp(recordedAt));
    FederatedReceivingRequest stored =
        jdbc.queryForObject(
            "SELECT * FROM transfer.federated_receiving_request "
                + "WHERE origin_deployment_id = ? AND request_operation_id = ? FOR UPDATE",
            ROW_MAPPER,
            request.originDeployment().value(),
            request.requestOperationId().value());
    if (stored == null) {
      throw new IllegalStateException("Federated receiving receipt disappeared after insert.");
    }
    return new Claim(
        stored, stored.requestFingerprint().equals(request.requestFingerprint()), inserted == 1);
  }

  @Override
  public Optional<FederatedReceivingRequest> find(InternalId requestId) {
    return jdbc
        .query(
            "SELECT * FROM transfer.federated_receiving_request WHERE receiving_request_id = ?",
            ROW_MAPPER,
            requestId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<FederatedReceivingRequest> lock(InternalId requestId) {
    return jdbc
        .query(
            "SELECT * FROM transfer.federated_receiving_request WHERE receiving_request_id = ? FOR UPDATE",
            ROW_MAPPER,
            requestId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<FederatedReceivingRequest> findByPeerOperation(
      DeploymentId peer, InternalId operationId) {
    return jdbc
        .query(
            "SELECT * FROM transfer.federated_receiving_request WHERE origin_deployment_id = ? "
                + "AND request_operation_id = ?",
            ROW_MAPPER,
            peer.value(),
            operationId.value())
        .stream()
        .findFirst();
  }

  @Override
  public boolean update(FederatedReceivingRequest request, long expectedVersion) {
    return jdbc.update(
            "UPDATE transfer.federated_receiving_request SET request_state = ?, "
                + "clinical_outcome = ?, clinical_reason_code = ?, clinical_actor_issuer = ?, "
                + "clinical_actor_type = ?, clinical_actor_id = ?, clinical_occurred_at = ?, "
                + "clinical_recorded_at = ?, operational_outcome = ?, operational_reason_code = ?, "
                + "operational_actor_issuer = ?, operational_actor_type = ?, operational_actor_id = ?, "
                + "operational_occurred_at = ?, operational_recorded_at = ?, accepted_by_issuer = ?, "
                + "accepted_by_type = ?, accepted_by_id = ?, accepted_at = ?, capacity_operation_id = ?, "
                + "capacity_reservation_status = ?, capacity_reservation_id = ?, capacity_reason_code = ?, "
                + "capacity_lifecycle_sequence = ?, request_version = ?, updated_at = ? "
                + "WHERE receiving_request_id = ? AND request_version = ?",
            request.status().name(),
            request.clinicalAssessment().map(a -> a.outcome().name()).orElse(null),
            request.clinicalAssessment().map(Assessment::reasonCode).orElse(null),
            request
                .clinicalAssessment()
                .map(a -> a.actor().identity().issuer().value())
                .orElse(null),
            request.clinicalAssessment().map(a -> a.actor().kind().name()).orElse(null),
            request.clinicalAssessment().map(a -> a.actor().identity().value()).orElse(null),
            request
                .clinicalAssessment()
                .map(Assessment::occurredAt)
                .map(JdbcFederatedReceivingRequestStore::timestamp)
                .orElse(null),
            request
                .clinicalAssessment()
                .map(Assessment::recordedAt)
                .map(JdbcFederatedReceivingRequestStore::timestamp)
                .orElse(null),
            request.operationalAssessment().map(a -> a.outcome().name()).orElse(null),
            request.operationalAssessment().map(Assessment::reasonCode).orElse(null),
            request
                .operationalAssessment()
                .map(a -> a.actor().identity().issuer().value())
                .orElse(null),
            request.operationalAssessment().map(a -> a.actor().kind().name()).orElse(null),
            request.operationalAssessment().map(a -> a.actor().identity().value()).orElse(null),
            request
                .operationalAssessment()
                .map(Assessment::occurredAt)
                .map(JdbcFederatedReceivingRequestStore::timestamp)
                .orElse(null),
            request
                .operationalAssessment()
                .map(Assessment::recordedAt)
                .map(JdbcFederatedReceivingRequestStore::timestamp)
                .orElse(null),
            request.acceptedBy().map(a -> a.identity().issuer().value()).orElse(null),
            request.acceptedBy().map(a -> a.kind().name()).orElse(null),
            request.acceptedBy().map(a -> a.identity().value()).orElse(null),
            request.acceptedAt().map(JdbcFederatedReceivingRequestStore::timestamp).orElse(null),
            request.capacityOperationId().map(InternalId::value).orElse(null),
            request.reservationStatus().name(),
            request.capacityReservationId().map(InternalId::value).orElse(null),
            request.capacityReasonCode().orElse(null),
            request.capacityLifecycleSequence(),
            request.version(),
            timestamp(request.updatedAt()),
            request.id().value(),
            expectedVersion)
        == 1;
  }

  @Override
  public void appendAssessment(InternalId requestId, Assessment assessment, long requestVersion) {
    jdbc.update(
        "INSERT INTO transfer.federated_receiving_assessment (receiving_request_id, "
            + "assessment_kind, request_version, assessment_outcome, reason_code, actor_issuer, "
            + "actor_type, actor_id, occurred_at, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        requestId.value(),
        assessment.kind().name(),
        requestVersion,
        assessment.outcome().name(),
        assessment.reasonCode(),
        assessment.actor().identity().issuer().value(),
        assessment.actor().kind().name(),
        assessment.actor().identity().value(),
        timestamp(assessment.occurredAt()),
        timestamp(assessment.recordedAt()));
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private static FederatedReceivingRequest map(ResultSet row, int index) throws SQLException {
    return new FederatedReceivingRequest(
        id(row, "receiving_request_id"),
        new DeploymentId(row.getString("origin_deployment_id")),
        id(row, "request_operation_id"),
        row.getString("request_fingerprint"),
        id(row, "origin_journey_id"),
        row.getString("origin_facility_id"),
        reference(row, "destination_organization"),
        reference(row, "destination_facility"),
        row.getString("capacity_dimension_code"),
        row.getString("capacity_unit"),
        row.getBigDecimal("capacity_quantity"),
        row.getTimestamp("capacity_interval_start").toInstant(),
        row.getTimestamp("capacity_interval_end").toInstant(),
        FederatedReceivingRequest.Status.valueOf(row.getString("request_state")),
        assessment(row, "clinical"),
        assessment(row, "operational"),
        actor(row, "accepted_by"),
        optionalInstant(row, "accepted_at"),
        optionalId(row, "capacity_operation_id"),
        FederatedReceivingRequest.ReservationStatus.valueOf(
            row.getString("capacity_reservation_status")),
        optionalId(row, "capacity_reservation_id"),
        Optional.ofNullable(row.getString("capacity_reason_code")),
        row.getLong("capacity_lifecycle_sequence"),
        row.getLong("request_version"),
        row.getTimestamp("received_at").toInstant(),
        row.getTimestamp("updated_at").toInstant());
  }

  private static ExternalReference reference(ResultSet row, String prefix) throws SQLException {
    return new ExternalReference(
        new IssuerId(row.getString(prefix + "_issuer")),
        prefix.equals("destination_organization") ? "Organization" : "Facility",
        row.getObject(prefix + "_id", UUID.class).toString());
  }

  private static Optional<FederatedReceivingRequest.Assessment> assessment(
      ResultSet row, String prefix) throws SQLException {
    String outcome = row.getString(prefix + "_outcome");
    if (outcome == null) {
      return Optional.empty();
    }
    return Optional.of(
        new FederatedReceivingRequest.Assessment(
            prefix.equals("clinical")
                ? FederatedReceivingRequest.AssessmentKind.CLINICAL
                : FederatedReceivingRequest.AssessmentKind.OPERATIONAL,
            FederatedReceivingRequest.Outcome.valueOf(outcome),
            row.getString(prefix + "_reason_code"),
            actor(row, prefix + "_actor").orElseThrow(),
            row.getTimestamp(prefix + "_occurred_at").toInstant(),
            row.getTimestamp(prefix + "_recorded_at").toInstant()));
  }

  private static Optional<ActorReference> actor(ResultSet row, String prefix) throws SQLException {
    String issuer = row.getString(prefix + "_issuer");
    String type = row.getString(prefix + "_type");
    String id = row.getString(prefix + "_id");
    if (issuer == null || type == null || id == null) {
      return Optional.empty();
    }
    ActorKind kind = ActorKind.valueOf(type);
    return Optional.of(
        kind == ActorKind.HUMAN
            ? ActorReference.human(new IssuerId(issuer), id)
            : ActorReference.service(new IssuerId(issuer), id));
  }

  private static Optional<Instant> optionalInstant(ResultSet row, String column)
      throws SQLException {
    var value = row.getTimestamp(column);
    return value == null ? Optional.empty() : Optional.of(value.toInstant());
  }

  private static Optional<InternalId> optionalId(ResultSet row, String column) throws SQLException {
    UUID value = row.getObject(column, UUID.class);
    return value == null ? Optional.empty() : Optional.of(new InternalId(value));
  }

  private static InternalId id(ResultSet row, String column) throws SQLException {
    return new InternalId(row.getObject(column, UUID.class));
  }
}
