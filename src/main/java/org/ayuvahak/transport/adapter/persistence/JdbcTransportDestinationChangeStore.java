package org.ayuvahak.transport.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.transport.application.TransportDestinationChangeStore;
import org.ayuvahak.transport.domain.TransportDestinationChangeOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC persistence adapter for Transport-owned destination-change operations. */
@Repository
public class JdbcTransportDestinationChangeStore implements TransportDestinationChangeStore {

  private final JdbcTemplate jdbc;

  public JdbcTransportDestinationChangeStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "JDBC template");
  }

  @Override
  public boolean insert(
      TransportDestinationChangeOperation operation,
      String initiatedByIssuer,
      String initiatedByIdentifierType) {
    return jdbc.update(
            "INSERT INTO transport.destination_change_operation (operation_id, "
                + "transfer_destination_change_id, transfer_id, transport_request_id, old_facility_id, "
                + "proposed_facility_id, reason_code, operation_state, initiated_by_kind, "
                + "initiated_by_issuer, initiated_by_identifier_type, initiated_by_identifier, "
                + "correlation_id, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (operation_id) DO NOTHING",
            operation.id().value(),
            operation.transferDestinationChangeId().value(),
            operation.transferId().value(),
            operation.transportRequestId().value(),
            operation.oldFacilityId().value(),
            operation.proposedFacilityId().value(),
            operation.reasonCode(),
            operation.initiatedBy().kind().name(),
            initiatedByIssuer,
            initiatedByIdentifierType,
            operation.initiatedBy().identity().value(),
            operation.correlationId().value(),
            operation.version(),
            Timestamp.from(operation.createdAt()),
            Timestamp.from(operation.updatedAt()))
        == 1;
  }

  @Override
  public Optional<TransportDestinationChangeOperation> find(InternalId operationId) {
    return select(operationId, false);
  }

  @Override
  public Optional<TransportDestinationChangeOperation> lock(InternalId operationId) {
    return select(operationId, true);
  }

  @Override
  public Optional<TransportDestinationChangeOperation> findByTransferOperation(
      InternalId transferOperationId) {
    return jdbc
        .query(
            "SELECT * FROM transport.destination_change_operation "
                + "WHERE transfer_destination_change_id = ?",
            JdbcTransportDestinationChangeStore::map,
            transferOperationId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TransportDestinationChangeOperation> findLatestAcceptedForRequest(
      InternalId transportRequestId) {
    return jdbc
        .query(
            "SELECT * FROM transport.destination_change_operation "
                + "WHERE transport_request_id = ? AND operation_state = 'RESOLVED' "
                + "AND outcome = 'ACCEPTED' ORDER BY resolved_at DESC, operation_id DESC LIMIT 1",
            JdbcTransportDestinationChangeStore::map,
            transportRequestId.value())
        .stream()
        .findFirst();
  }

  @Override
  public List<TransportDestinationChangeOperation> findAcceptedHistoryForRequest(
      InternalId transportRequestId) {
    return jdbc.query(
        "SELECT * FROM transport.destination_change_operation "
            + "WHERE transport_request_id = ? AND operation_state = 'RESOLVED' "
            + "AND outcome = 'ACCEPTED' ORDER BY source_occurred_at, operation_id",
        JdbcTransportDestinationChangeStore::map,
        transportRequestId.value());
  }

  @Override
  public boolean markUncertain(
      InternalId operationId,
      long expectedVersion,
      TransportDestinationChangeOperation.State state,
      Instant updatedAt) {
    if (state != TransportDestinationChangeOperation.State.OUTCOME_UNKNOWN
        && state != TransportDestinationChangeOperation.State.RECONCILIATION_REQUIRED) {
      throw new IllegalArgumentException("an unresolved destination-change state is required");
    }
    return jdbc.update(
            "UPDATE transport.destination_change_operation SET operation_state = ?, "
                + "version = version + 1, updated_at = ? WHERE operation_id = ? AND version = ? "
                + "AND operation_state IN ('PENDING', 'OUTCOME_UNKNOWN')",
            state.name(),
            Timestamp.from(updatedAt),
            operationId.value(),
            expectedVersion)
        == 1;
  }

  @Override
  public boolean resolve(
      InternalId operationId,
      long expectedVersion,
      TransportDestinationChangeOperation.Outcome outcome,
      ActorReference recordedBy,
      String sourceNamespace,
      String sourceObservationId,
      String fingerprint,
      Instant sourceOccurredAt,
      Instant resolvedAt,
      InternalId eventId) {
    return jdbc.update(
            "UPDATE transport.destination_change_operation SET operation_state = 'RESOLVED', "
                + "outcome = ?, recorded_by_kind = ?, recorded_by_issuer = ?, "
                + "recorded_by_identifier_type = ?, recorded_by_identifier = ?, source_namespace = ?, "
                + "source_observation_id = ?, resolution_fingerprint = ?, source_occurred_at = ?, "
                + "resolved_at = ?, resolution_event_id = ?, version = version + 1, updated_at = ? "
                + "WHERE operation_id = ? AND operation_state <> 'RESOLVED' AND version = ?",
            outcome.name(),
            recordedBy.kind().name(),
            recordedBy.identity().issuer().value(),
            recordedBy.identity().resourceType(),
            recordedBy.identity().value(),
            sourceNamespace,
            sourceObservationId,
            fingerprint,
            Timestamp.from(sourceOccurredAt),
            Timestamp.from(resolvedAt),
            eventId.value(),
            Timestamp.from(resolvedAt),
            operationId.value(),
            expectedVersion)
        == 1;
  }

  private Optional<TransportDestinationChangeOperation> select(
      InternalId operationId, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM transport.destination_change_operation WHERE operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            JdbcTransportDestinationChangeStore::map,
            operationId.value())
        .stream()
        .findFirst();
  }

  private static TransportDestinationChangeOperation map(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    return new TransportDestinationChangeOperation(
        id(result, "operation_id"),
        id(result, "transfer_destination_change_id"),
        id(result, "transfer_id"),
        id(result, "transport_request_id"),
        id(result, "old_facility_id"),
        id(result, "proposed_facility_id"),
        result.getString("reason_code"),
        TransportDestinationChangeOperation.State.valueOf(result.getString("operation_state")),
        Optional.ofNullable(result.getString("outcome"))
            .map(TransportDestinationChangeOperation.Outcome::valueOf),
        actor(result, "initiated_by"),
        nullableActor(result, "recorded_by"),
        Optional.ofNullable(result.getString("source_namespace")),
        Optional.ofNullable(result.getString("source_observation_id")),
        Optional.ofNullable(result.getString("resolution_fingerprint")).map(String::trim),
        Optional.ofNullable(result.getTimestamp("source_occurred_at")).map(Timestamp::toInstant),
        Optional.ofNullable(result.getTimestamp("resolved_at")).map(Timestamp::toInstant),
        Optional.ofNullable(result.getObject("resolution_event_id", UUID.class))
            .map(InternalId::new),
        new org.ayuvahak.platform.core.CorrelationId(result.getString("correlation_id")),
        result.getLong("version"),
        result.getTimestamp("created_at").toInstant(),
        result.getTimestamp("updated_at").toInstant());
  }

  private static ActorReference actor(java.sql.ResultSet result, String prefix)
      throws java.sql.SQLException {
    ActorKind kind = ActorKind.valueOf(result.getString(prefix + "_kind"));
    return new ActorReference(
        kind,
        new org.ayuvahak.platform.core.ExternalReference(
            new IssuerId(result.getString(prefix + "_issuer")),
            result.getString(prefix + "_identifier_type"),
            result.getString(prefix + "_identifier")));
  }

  private static Optional<ActorReference> nullableActor(java.sql.ResultSet result, String prefix)
      throws java.sql.SQLException {
    return result.getString(prefix + "_kind") == null
        ? Optional.empty()
        : Optional.of(actor(result, prefix));
  }

  private static InternalId id(java.sql.ResultSet result, String column)
      throws java.sql.SQLException {
    return new InternalId(result.getObject(column, UUID.class));
  }
}
