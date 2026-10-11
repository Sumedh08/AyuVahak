package org.ayuvahak.transfer.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transfer.application.TransferViewStore;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL queries over Transfer-owned tables only. */
@Repository
public class JdbcTransferViewStore implements TransferViewStore {

  private final JdbcTemplate jdbc;

  public JdbcTransferViewStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<JourneyRows> journey(
      InternalId journeyId, ExecutionScope scope, long offset, int limit) {
    String sql =
        "SELECT j.journey_id, j.referral_id, j.origin_organization_id, j.origin_facility_id, "
            + "j.created_at, j.version AS journey_version, a.request_attempt_id, a.attempt_number, "
            + "a.referral_id AS attempt_referral_id, a.referral_revision, a.recommendation_id, "
            + "a.destination_facility_id, a.request_status, a.requested_at, a.expires_at, "
            + "a.terminal_occurred_at, a.terminal_recorded_at, clinical.outcome AS clinical_outcome, "
            + "clinical.reason_code AS clinical_reason, operational.outcome AS operational_outcome, "
            + "operational.reason_code AS operational_reason, d.decision_kind, d.reason_code AS decision_reason, "
            + "t.transfer_id, t.transfer_status, reservation.outcome AS reservation_outcome, "
            + "reservation.reservation_id "
            + "FROM transfer.journey j "
            + "LEFT JOIN LATERAL (SELECT request_attempt_id, journey_id, attempt_number, referral_id, "
            + "referral_revision, recommendation_id, destination_facility_id, request_status, "
            + "requested_at, expires_at, terminal_occurred_at, terminal_recorded_at "
            + "FROM transfer.request_attempt a WHERE a.journey_id = j.journey_id AND "
            + journeyAttemptParticipantPredicate(scope)
            + " ORDER BY attempt_number, request_attempt_id LIMIT ? OFFSET ?) a ON TRUE "
            + "LEFT JOIN LATERAL (SELECT outcome, reason_code FROM transfer.receiving_assessment "
            + "WHERE request_attempt_id = a.request_attempt_id AND assessment_kind = 'CLINICAL_SUITABILITY' "
            + "ORDER BY request_version DESC LIMIT 1) clinical ON TRUE "
            + "LEFT JOIN LATERAL (SELECT outcome, reason_code FROM transfer.receiving_assessment "
            + "WHERE request_attempt_id = a.request_attempt_id AND assessment_kind = 'OPERATIONAL_READINESS' "
            + "ORDER BY request_version DESC LIMIT 1) operational ON TRUE "
            + "LEFT JOIN transfer.request_decision d ON d.request_attempt_id = a.request_attempt_id "
            + "LEFT JOIN transfer.transfer_record t ON t.request_attempt_id = a.request_attempt_id "
            + "LEFT JOIN transfer.capacity_reservation_request reservation ON reservation.transfer_id = t.transfer_id "
            + "WHERE j.journey_id = ? AND "
            + journeyParticipantPredicate(scope)
            + " ORDER BY a.attempt_number NULLS LAST, a.request_attempt_id NULLS LAST";

    List<Object> parameters = new ArrayList<>();
    parameters.addAll(List.of(scopeParameters(scope)));
    parameters.add(limit);
    parameters.add(offset);
    parameters.add(journeyId.value());
    parameters.addAll(List.of(scopeParameters(scope)));
    List<JourneyRows> rows =
        jdbc.query(sql, (result, row) -> journeyRow(result), parameters.toArray());
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    JourneyRows first = rows.getFirst();
    List<RequestAttemptView> attempts =
        rows.stream().flatMap(row -> row.attempts().stream()).toList();
    return Optional.of(
        new JourneyRows(
            first.journeyId(),
            first.referralId(),
            first.originOrganizationId(),
            first.originFacilityId(),
            first.createdAt(),
            first.version(),
            attempts));
  }

  @Override
  public List<IncomingRequest> pendingIncoming(
      InternalId receivingFacilityId, long offset, int limit) {
    return jdbc.query(
        "SELECT a.request_attempt_id, a.journey_id, a.referral_id, a.referral_revision, "
            + "a.recommendation_id, a.destination_facility_id, a.requested_at, a.expires_at, "
            + "j.origin_organization_id, j.origin_facility_id "
            + "FROM transfer.request_attempt a JOIN transfer.journey j ON j.journey_id = a.journey_id "
            + "WHERE a.request_status = 'PENDING' AND a.destination_facility_id = ? "
            + "ORDER BY a.expires_at ASC, a.request_attempt_id ASC LIMIT ? OFFSET ?",
        JdbcTransferViewStore::incomingRequest,
        receivingFacilityId.value(),
        limit,
        offset);
  }

  @Override
  public List<TransferSummary> transfers(
      ExecutionScope scope, Optional<CoordinatedTransfer.Status> status, long offset, int limit) {
    List<Object> parameters = new ArrayList<>(List.of(transferScopeParameters(scope)));
    status.ifPresent(value -> parameters.add(value.name()));
    parameters.add(limit);
    parameters.add(offset);
    return jdbc.query(
        transferSelect()
            + "WHERE "
            + transferParticipantPredicate(scope)
            + statusPredicate(status)
            + " ORDER BY last_event_at DESC, last_aggregate_sequence DESC, t.transfer_id ASC "
            + "LIMIT ? OFFSET ?",
        JdbcTransferViewStore::transferSummary,
        parameters.toArray());
  }

  @Override
  public Optional<TransferSummary> transfer(InternalId transferId, ExecutionScope scope) {
    List<Object> parameters = new ArrayList<>();
    parameters.add(transferId.value());
    parameters.addAll(List.of(transferScopeParameters(scope)));
    List<TransferSummary> rows =
        jdbc.query(
            transferSelect() + "WHERE t.transfer_id = ? AND " + transferParticipantPredicate(scope),
            JdbcTransferViewStore::transferSummary,
            parameters.toArray());
    return rows.stream().findFirst();
  }

  private String transferSelect() {
    return "SELECT t.transfer_id, t.journey_id, t.request_attempt_id, a.referral_id, "
        + "a.recommendation_id, j.origin_organization_id, j.origin_facility_id, "
        + "d.receiving_organization_id, d.receiving_facility_id, t.transfer_status, t.version, "
        + "t.created_at, COALESCE(progress.last_event_at, t.created_at) AS last_event_at, "
        + "COALESCE(progress.last_aggregate_sequence, 0) AS last_aggregate_sequence, "
        + "reservation.outcome AS reservation_outcome, reservation.reservation_id "
        + "FROM transfer.transfer_record t "
        + "JOIN transfer.journey j ON j.journey_id = t.journey_id "
        + "JOIN transfer.request_attempt a ON a.request_attempt_id = t.request_attempt_id "
        + "JOIN transfer.request_decision d ON d.request_attempt_id = a.request_attempt_id "
        + "LEFT JOIN transfer.capacity_reservation_request reservation ON reservation.transfer_id = t.transfer_id "
        + "LEFT JOIN LATERAL (SELECT occurred_at AS last_event_at, aggregate_sequence AS last_aggregate_sequence "
        + "FROM transfer.progress_fact WHERE transfer_id = t.transfer_id "
        + "ORDER BY occurred_at DESC, aggregate_sequence DESC LIMIT 1) progress ON TRUE ";
  }

  private String journeyParticipantPredicate(ExecutionScope scope) {
    return switch (scope.kind()) {
      case PLATFORM -> "FALSE";
      case ORGANIZATION ->
          "(j.origin_organization_id = ? OR EXISTS (SELECT 1 FROM transfer.receiving_assessment r "
              + "WHERE r.request_attempt_id IN (SELECT request_attempt_id FROM transfer.request_attempt "
              + "WHERE journey_id = j.journey_id) AND r.receiving_organization_id = ?) "
              + "OR EXISTS (SELECT 1 FROM transfer.request_decision d "
              + "JOIN transfer.request_attempt a2 ON a2.request_attempt_id = d.request_attempt_id "
              + "WHERE a2.journey_id = j.journey_id AND d.receiving_organization_id = ?))";
      case FACILITY ->
          "((j.origin_organization_id = ? AND j.origin_facility_id = ?) "
              + "OR EXISTS (SELECT 1 FROM transfer.request_attempt a2 "
              + "WHERE a2.journey_id = j.journey_id AND a2.destination_facility_id = ?) "
              + "OR EXISTS (SELECT 1 FROM transfer.receiving_assessment r "
              + "WHERE r.request_attempt_id IN (SELECT request_attempt_id FROM transfer.request_attempt "
              + "WHERE journey_id = j.journey_id) AND r.receiving_organization_id = ? AND r.receiving_facility_id = ?) "
              + "OR EXISTS (SELECT 1 FROM transfer.request_decision d "
              + "JOIN transfer.request_attempt a2 ON a2.request_attempt_id = d.request_attempt_id "
              + "WHERE a2.journey_id = j.journey_id AND d.receiving_organization_id = ? "
              + "AND d.receiving_facility_id = ?))";
    };
  }

  private String journeyAttemptParticipantPredicate(ExecutionScope scope) {
    return switch (scope.kind()) {
      case PLATFORM -> "FALSE";
      case ORGANIZATION ->
          "(j.origin_organization_id = ? OR EXISTS (SELECT 1 FROM transfer.receiving_assessment r "
              + "WHERE r.request_attempt_id = a.request_attempt_id AND r.receiving_organization_id = ?) "
              + "OR EXISTS (SELECT 1 FROM transfer.request_decision d "
              + "WHERE d.request_attempt_id = a.request_attempt_id AND d.receiving_organization_id = ?))";
      case FACILITY ->
          "((j.origin_organization_id = ? AND j.origin_facility_id = ?) "
              + "OR a.destination_facility_id = ? "
              + "OR EXISTS (SELECT 1 FROM transfer.receiving_assessment r "
              + "WHERE r.request_attempt_id = a.request_attempt_id AND r.receiving_organization_id = ? "
              + "AND r.receiving_facility_id = ?) "
              + "OR EXISTS (SELECT 1 FROM transfer.request_decision d "
              + "WHERE d.request_attempt_id = a.request_attempt_id AND d.receiving_organization_id = ? "
              + "AND d.receiving_facility_id = ?))";
    };
  }

  private Object[] scopeParameters(ExecutionScope scope) {
    UUID organization = directoryId(scope.organization().orElseThrow().value());
    if (scope.kind() == ExecutionScope.Kind.ORGANIZATION) {
      return new Object[] {organization, organization, organization};
    }
    UUID facility = directoryId(scope.facility().orElseThrow().value());
    return new Object[] {
      organization, facility, facility, organization, facility, organization, facility
    };
  }

  private Object[] transferScopeParameters(ExecutionScope scope) {
    UUID organization = directoryId(scope.organization().orElseThrow().value());
    return switch (scope.kind()) {
      case PLATFORM -> new Object[0];
      case ORGANIZATION -> new Object[] {organization, organization};
      case FACILITY -> {
        UUID facility = directoryId(scope.facility().orElseThrow().value());
        yield new Object[] {organization, facility, organization, facility};
      }
    };
  }

  private String transferParticipantPredicate(ExecutionScope scope) {
    return switch (scope.kind()) {
      case PLATFORM -> "FALSE";
      case ORGANIZATION -> "(j.origin_organization_id = ? OR d.receiving_organization_id = ?)";
      case FACILITY ->
          "((j.origin_organization_id = ? AND j.origin_facility_id = ?) "
              + "OR (d.receiving_organization_id = ? AND d.receiving_facility_id = ?))";
    };
  }

  private String statusPredicate(Optional<CoordinatedTransfer.Status> status) {
    return status.isPresent() ? " AND t.transfer_status = ? " : " ";
  }

  private JourneyRows journeyRow(ResultSet result) throws SQLException {
    UUID originFacility = result.getObject("origin_facility_id", UUID.class);
    List<RequestAttemptView> attempts = new ArrayList<>();
    if (result.getObject("request_attempt_id", UUID.class) != null) {
      attempts.add(
          new RequestAttemptView(
              id(result, "request_attempt_id"),
              result.getInt("attempt_number"),
              id(result, "attempt_referral_id"),
              result.getLong("referral_revision"),
              id(result, "recommendation_id"),
              id(result, "destination_facility_id"),
              TransferRequestAttempt.Status.valueOf(result.getString("request_status")),
              instant(result, "requested_at"),
              instant(result, "expires_at"),
              optionalInstant(result, "terminal_occurred_at"),
              optionalInstant(result, "terminal_recorded_at"),
              optionalString(result, "clinical_outcome"),
              optionalString(result, "clinical_reason"),
              optionalString(result, "operational_outcome"),
              optionalString(result, "operational_reason"),
              optionalString(result, "decision_kind"),
              optionalString(result, "decision_reason"),
              optionalId(result, "transfer_id"),
              optionalString(result, "transfer_status"),
              optionalString(result, "reservation_outcome"),
              optionalId(result, "reservation_id")));
    }
    return new JourneyRows(
        id(result, "journey_id"),
        id(result, "referral_id"),
        id(result, "origin_organization_id"),
        Optional.ofNullable(originFacility).map(InternalId::new),
        instant(result, "created_at"),
        result.getLong("journey_version"),
        attempts);
  }

  private static IncomingRequest incomingRequest(ResultSet result, int row) throws SQLException {
    return new IncomingRequest(
        id(result, "request_attempt_id"),
        id(result, "journey_id"),
        id(result, "referral_id"),
        result.getLong("referral_revision"),
        id(result, "recommendation_id"),
        id(result, "origin_organization_id"),
        optionalId(result, "origin_facility_id"),
        id(result, "destination_facility_id"),
        instant(result, "requested_at"),
        instant(result, "expires_at"));
  }

  private static TransferSummary transferSummary(ResultSet result, int row) throws SQLException {
    return new TransferSummary(
        id(result, "transfer_id"),
        id(result, "journey_id"),
        id(result, "request_attempt_id"),
        id(result, "referral_id"),
        id(result, "recommendation_id"),
        id(result, "origin_organization_id"),
        optionalId(result, "origin_facility_id"),
        id(result, "receiving_organization_id"),
        id(result, "receiving_facility_id"),
        CoordinatedTransfer.Status.valueOf(result.getString("transfer_status")),
        result.getLong("version"),
        instant(result, "created_at"),
        instant(result, "last_event_at"),
        result.getLong("last_aggregate_sequence"),
        optionalString(result, "reservation_outcome"),
        optionalId(result, "reservation_id"));
  }

  private static InternalId id(ResultSet result, String column) throws SQLException {
    return new InternalId(result.getObject(column, UUID.class));
  }

  private static Optional<InternalId> optionalId(ResultSet result, String column)
      throws SQLException {
    return Optional.ofNullable(result.getObject(column, UUID.class)).map(InternalId::new);
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    return result.getTimestamp(column).toInstant();
  }

  private static Optional<Instant> optionalInstant(ResultSet result, String column)
      throws SQLException {
    return Optional.ofNullable(result.getTimestamp(column)).map(Timestamp::toInstant);
  }

  private static Optional<String> optionalString(ResultSet result, String column)
      throws SQLException {
    return Optional.ofNullable(result.getString(column));
  }

  private static UUID directoryId(String value) {
    return UUID.fromString(value);
  }
}
