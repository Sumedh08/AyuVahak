package org.ayuvahak.transport.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transport.application.TransportRequestReadStore;
import org.ayuvahak.transport.domain.TransportRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL read projection for bounded Transport participant and operator views. */
@Repository
public class JdbcTransportRequestReadStore implements TransportRequestReadStore {

  private final JdbcTemplate jdbc;

  public JdbcTransportRequestReadStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "JDBC template");
  }

  @Override
  public List<RequestRow> requestsForTransfer(InternalId transferId, long offset, int limit) {
    return jdbc.query(
        "SELECT transport_request_id, transfer_id, origin_facility_id, destination_facility_id, "
            + "request_status, requested_at, correlation_id, version "
            + "FROM transport.transport_request WHERE transfer_id = ? "
            + "ORDER BY requested_at DESC, transport_request_id DESC LIMIT ? OFFSET ?",
        JdbcTransportRequestReadStore::requestRow,
        transferId.value(),
        limit,
        offset);
  }

  @Override
  public Optional<RequestRow> request(InternalId requestId) {
    List<RequestRow> rows =
        jdbc.query(
            "SELECT transport_request_id, transfer_id, origin_facility_id, destination_facility_id, "
                + "request_status, requested_at, correlation_id, version "
                + "FROM transport.transport_request WHERE transport_request_id = ?",
            JdbcTransportRequestReadStore::requestRow,
            requestId.value());
    return rows.stream().findFirst();
  }

  @Override
  public List<StatusRow> statuses(InternalId requestId, int limit) {
    return jdbc.query(
        "SELECT status_observation_id, source_namespace, source_observation_id, status_code, "
            + "occurred_at, recorded_at, observation_outcome, request_status_after, reason_code, "
            + "resulting_request_version FROM transport.status_observation "
            + "WHERE transport_request_id = ? ORDER BY occurred_at, recorded_at, status_observation_id LIMIT ?",
        (row, index) ->
            new StatusRow(
                id(row, "status_observation_id"),
                row.getString("source_namespace"),
                row.getString("source_observation_id"),
                TransportRequest.Status.valueOf(row.getString("status_code")),
                row.getTimestamp("occurred_at").toInstant(),
                row.getTimestamp("recorded_at").toInstant(),
                row.getString("observation_outcome"),
                TransportRequest.Status.valueOf(row.getString("request_status_after")),
                Optional.ofNullable(row.getString("reason_code")),
                row.getLong("resulting_request_version")),
        requestId.value(),
        limit);
  }

  @Override
  public List<AssignmentRow> assignments(InternalId requestId, int limit) {
    return jdbc.query(
        "SELECT assignment_observation_id, source_namespace, source_observation_id, "
            + "assignment_reference, occurred_at, recorded_at, observation_outcome, "
            + "request_status_after, resulting_request_version "
            + "FROM transport.assignment_observation WHERE transport_request_id = ? "
            + "ORDER BY occurred_at, recorded_at, assignment_observation_id LIMIT ?",
        (row, index) ->
            new AssignmentRow(
                id(row, "assignment_observation_id"),
                row.getString("source_namespace"),
                row.getString("source_observation_id"),
                row.getString("assignment_reference"),
                row.getTimestamp("occurred_at").toInstant(),
                row.getTimestamp("recorded_at").toInstant(),
                row.getString("observation_outcome"),
                TransportRequest.Status.valueOf(row.getString("request_status_after")),
                row.getLong("resulting_request_version")),
        requestId.value(),
        limit);
  }

  @Override
  public List<EtaRow> etas(InternalId requestId, int limit) {
    return jdbc.query(
        "SELECT eta_observation_id, source_namespace, source_observation_id, "
            + "estimated_arrival_at, observed_at, recorded_at, valid_until "
            + "FROM transport.eta_observation WHERE transport_request_id = ? "
            + "ORDER BY observed_at, recorded_at, eta_observation_id LIMIT ?",
        (row, index) ->
            new EtaRow(
                id(row, "eta_observation_id"),
                row.getString("source_namespace"),
                row.getString("source_observation_id"),
                row.getTimestamp("estimated_arrival_at").toInstant(),
                row.getTimestamp("observed_at").toInstant(),
                row.getTimestamp("recorded_at").toInstant(),
                row.getTimestamp("valid_until").toInstant()),
        requestId.value(),
        limit);
  }

  @Override
  public List<CancellationRow> cancellations(InternalId requestId, int limit) {
    return jdbc.query(
        "SELECT cancellation_operation_id, reason_code, operation_state, outcome, initiated_at, "
            + "resolved_at, version FROM transport.cancellation_operation "
            + "WHERE transport_request_id = ? ORDER BY initiated_at, cancellation_operation_id LIMIT ?",
        (row, index) ->
            new CancellationRow(
                id(row, "cancellation_operation_id"),
                row.getString("reason_code"),
                row.getString("operation_state"),
                Optional.ofNullable(row.getString("outcome")),
                row.getTimestamp("initiated_at").toInstant(),
                optionalInstant(row, "resolved_at"),
                row.getLong("version")),
        requestId.value(),
        limit);
  }

  @Override
  public List<DestinationChangeRow> destinationChanges(InternalId requestId, int limit) {
    return jdbc.query(
        "SELECT operation_id, transfer_destination_change_id, transfer_id, old_facility_id, "
            + "proposed_facility_id, reason_code, operation_state, outcome, created_at, updated_at, version "
            + "FROM transport.destination_change_operation WHERE transport_request_id = ? "
            + "ORDER BY created_at, operation_id LIMIT ?",
        (row, index) ->
            new DestinationChangeRow(
                id(row, "operation_id"),
                id(row, "transfer_destination_change_id"),
                id(row, "transfer_id"),
                id(row, "old_facility_id"),
                id(row, "proposed_facility_id"),
                row.getString("reason_code"),
                row.getString("operation_state"),
                Optional.ofNullable(row.getString("outcome")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version")),
        requestId.value(),
        limit);
  }

  @Override
  public List<ProviderSubmissionRow> providerSubmissions(InternalId requestId, int limit) {
    return jdbc.query(
        "SELECT operation_reference, operation_kind, partner_key, submission_state, "
            + "configuration_version, response_status, failure_code, created_at, updated_at, version "
            + "FROM transport.provider_submission WHERE transport_request_id = ? "
            + "ORDER BY created_at, operation_kind, operation_reference LIMIT ?",
        (row, index) ->
            new ProviderSubmissionRow(
                id(row, "operation_reference"),
                row.getString("operation_kind"),
                row.getString("partner_key"),
                row.getString("submission_state"),
                Optional.ofNullable((Integer) row.getObject("configuration_version")),
                Optional.ofNullable((Integer) row.getObject("response_status")),
                Optional.ofNullable(row.getString("failure_code")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version")),
        requestId.value(),
        limit);
  }

  private static RequestRow requestRow(java.sql.ResultSet row, int index)
      throws java.sql.SQLException {
    return new RequestRow(
        id(row, "transport_request_id"),
        id(row, "transfer_id"),
        id(row, "origin_facility_id"),
        id(row, "destination_facility_id"),
        TransportRequest.Status.valueOf(row.getString("request_status")),
        row.getTimestamp("requested_at").toInstant(),
        new CorrelationId(row.getString("correlation_id")),
        row.getLong("version"));
  }

  private static InternalId id(java.sql.ResultSet row, String column) throws java.sql.SQLException {
    return InternalId.parse(row.getObject(column, UUID.class).toString());
  }

  private static Optional<java.time.Instant> optionalInstant(java.sql.ResultSet row, String column)
      throws java.sql.SQLException {
    var timestamp = row.getTimestamp(column);
    return timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant());
  }
}
