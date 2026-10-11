package org.ayuvahak.capacity.adapter.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.capacity.application.CapacityReservationStore;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for Capacity-owned coordination reservations. */
@Repository
@Lazy
public class JdbcCapacityReservationStore implements CapacityReservationStore {

  private final JdbcTemplate jdbc;

  public JdbcCapacityReservationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ReceiptClaim claimReceipt(
      CommandReceiptKey key,
      RequestFingerprint fingerprint,
      InternalId operationId,
      Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO capacity.reservation_command_receipt (scope_digest, "
                + "idempotency_key_digest, request_fingerprint, operation_id, receipt_state, recorded_at) "
                + "VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?) ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            operationId.value(),
            Timestamp.from(recordedAt));
    if (inserted == 1) {
      return new ReceiptClaim(ReceiptState.OWNER, Optional.empty());
    }
    List<ReceiptRow> rows =
        jdbc.query(
            "SELECT request_fingerprint, operation_id, receipt_state, outcome, reservation_id, reason_code "
                + "FROM capacity.reservation_command_receipt "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? FOR UPDATE",
            (result, row) ->
                new ReceiptRow(
                    result.getString("request_fingerprint"),
                    result.getObject("operation_id", UUID.class),
                    result.getString("receipt_state"),
                    result.getString("outcome"),
                    result.getObject("reservation_id", UUID.class),
                    result.getString("reason_code")),
            key.scopeDigest(),
            key.idempotencyKey().value());
    ReceiptRow row = rows.stream().findFirst().orElseThrow();
    if (!fingerprint.value().equals(row.fingerprint())
        || !operationId.value().equals(row.operationId())) {
      return new ReceiptClaim(ReceiptState.KEY_REUSED, Optional.empty());
    }
    if ("IN_PROGRESS".equals(row.state())) {
      return new ReceiptClaim(ReceiptState.IN_PROGRESS, Optional.empty());
    }
    return new ReceiptClaim(
        ReceiptState.REPLAY,
        Optional.of(
            new CapacityReservationResult(
                new InternalId(row.operationId()),
                CapacityReservationResult.Outcome.valueOf(row.outcome()),
                Optional.ofNullable(row.reservationId()).map(InternalId::new),
                Optional.ofNullable(row.reasonCode()),
                true)));
  }

  @Override
  public void completeReceipt(
      CommandReceiptKey key,
      RequestFingerprint fingerprint,
      CapacityReservationResult result,
      Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE capacity.reservation_command_receipt SET receipt_state = 'COMPLETED', "
                + "outcome = ?, reservation_id = ?, reason_code = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? "
                + "AND request_fingerprint = ? AND operation_id = ? AND receipt_state = 'IN_PROGRESS'",
            result.outcome().name(),
            result.reservationId().map(InternalId::value).orElse(null),
            result.reasonCode().orElse(null),
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            result.operationId().value());
    if (changed != 1) {
      throw new IllegalStateException(
          "Capacity reservation command receipt could not be completed");
    }
  }

  @Override
  public OperationClaim lockOperation(
      InternalId operationId,
      InternalId transferId,
      RequestFingerprint fingerprint,
      Instant recordedAt) {
    jdbc.update(
        "INSERT INTO capacity.reservation_operation (operation_id, transfer_id, "
            + "request_fingerprint, outcome, recorded_at) VALUES (?, ?, ?, 'PENDING', ?) "
            + "ON CONFLICT (operation_id) DO NOTHING",
        operationId.value(),
        transferId.value(),
        fingerprint.value(),
        Timestamp.from(recordedAt));
    OperationRow row =
        jdbc.queryForObject(
            "SELECT transfer_id, request_fingerprint, outcome, reservation_id, reason_code "
                + "FROM capacity.reservation_operation WHERE operation_id = ? FOR UPDATE",
            (result, rowNumber) ->
                new OperationRow(
                    result.getObject("transfer_id", UUID.class),
                    result.getString("request_fingerprint"),
                    result.getString("outcome"),
                    result.getObject("reservation_id", UUID.class),
                    result.getString("reason_code")),
            operationId.value());
    boolean matches =
        transferId.value().equals(row.transferId())
            && fingerprint.value().equals(row.fingerprint());
    Optional<CapacityReservationResult> current =
        matches && !"PENDING".equals(row.outcome())
            ? Optional.of(result(operationId, row, true))
            : Optional.empty();
    return new OperationClaim(matches, current);
  }

  @Override
  public BigDecimal sumActiveReservations(
      String facilityIssuer,
      String facilityId,
      String dimensionCode,
      String unit,
      Instant intervalStart,
      Instant intervalEnd,
      Instant at) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(quantity), 0) FROM capacity.coordination_reservation "
            + "WHERE facility_issuer = ? AND facility_identifier = ?::uuid "
            + "AND dimension_code = ? AND unit_code = ? AND expires_at > ? "
            + "AND reservation_status IN ('ACTIVE', 'AT_RISK') "
            + "AND interval_start < ? AND interval_end > ?",
        BigDecimal.class,
        facilityIssuer,
        facilityId,
        dimensionCode,
        unit,
        Timestamp.from(at),
        Timestamp.from(intervalEnd),
        Timestamp.from(intervalStart));
  }

  @Override
  public void createReservation(
      CapacityReservationRequest request,
      long dimensionVersion,
      long policyVersion,
      List<InternalId> evidenceIds,
      InternalId reservationId,
      Instant createdAt) {
    String evidenceJson =
        evidenceIds.stream()
            .map(id -> "\"" + id + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    jdbc.update(
        "INSERT INTO capacity.coordination_reservation (reservation_id, operation_id, "
            + "transfer_id, facility_issuer, facility_identifier_type, facility_identifier, "
            + "dimension_code, dimension_version, policy_version, unit_code, quantity, "
            + "interval_start, interval_end, expires_at, basis_observation_ids, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
        reservationId.value(),
        request.operationId().value(),
        request.transferId().value(),
        request.facility().issuer().value(),
        request.facility().resourceType(),
        request.facility().value(),
        request.dimensionCode(),
        dimensionVersion,
        policyVersion,
        request.unit(),
        request.quantity(),
        Timestamp.from(request.intervalStart()),
        Timestamp.from(request.intervalEnd()),
        Timestamp.from(request.expiresAt()),
        evidenceJson,
        Timestamp.from(createdAt));
  }

  @Override
  public void recordOutcome(
      InternalId operationId,
      CapacityReservationResult.Outcome outcome,
      Optional<InternalId> reservationId,
      Optional<String> reasonCode,
      Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE capacity.reservation_operation SET outcome = ?, reservation_id = ?, "
                + "reason_code = ?, recorded_at = ? WHERE operation_id = ?",
            outcome.name(),
            reservationId.map(InternalId::value).orElse(null),
            reasonCode.orElse(null),
            Timestamp.from(recordedAt),
            operationId.value());
    if (changed != 1) {
      throw new IllegalStateException("Capacity reservation operation disappeared while locked");
    }
  }

  @Override
  public Optional<CapacityReservationResult> findOperation(InternalId operationId) {
    return jdbc
        .query(
            "SELECT request_fingerprint, outcome, reservation_id, reason_code "
                + "FROM capacity.reservation_operation WHERE operation_id = ?",
            (result, rowNumber) ->
                new OperationRow(
                    null,
                    result.getString("request_fingerprint"),
                    result.getString("outcome"),
                    result.getObject("reservation_id", UUID.class),
                    result.getString("reason_code")),
            operationId.value())
        .stream()
        .filter(row -> !"PENDING".equals(row.outcome()))
        .findFirst()
        .map(row -> result(operationId, row, true));
  }

  @Override
  public ReleaseResult release(
      InternalId transferId, String facilityIdentifier, Instant releasedAt) {
    List<ReservationOperationLockRow> operation =
        jdbc.query(
            "SELECT o.outcome, o.reservation_id, r.facility_identifier "
                + "FROM capacity.reservation_operation o LEFT JOIN capacity.coordination_reservation r "
                + "ON r.reservation_id = o.reservation_id WHERE o.transfer_id = ? FOR UPDATE OF o",
            (result, rowNumber) ->
                new ReservationOperationLockRow(
                    result.getString("outcome"),
                    result.getObject("reservation_id", UUID.class),
                    result.getObject("facility_identifier", UUID.class)),
            transferId.value());
    if (operation.isEmpty() || !"RESERVED".equals(operation.getFirst().outcome())) {
      return new ReleaseResult(
          ReleaseOutcome.NOT_RESERVED, Optional.empty(), 0, false, Optional.empty());
    }
    UUID reservedFacility = operation.getFirst().facilityIdentifier();
    if (reservedFacility != null && !reservedFacility.toString().equals(facilityIdentifier)) {
      return new ReleaseResult(
          ReleaseOutcome.SCOPE_MISMATCH, Optional.empty(), 0, false, Optional.empty());
    }
    List<ReservationLifecycleRow> changed =
        jdbc.query(
            "UPDATE capacity.coordination_reservation SET reservation_status = "
                + "CASE WHEN expires_at <= ? THEN 'EXPIRED' ELSE 'RELEASED' END, "
                + "lifecycle_sequence = lifecycle_sequence + 1, status_recorded_at = ? "
                + "WHERE transfer_id = ? AND reservation_status IN ('ACTIVE', 'AT_RISK') "
                + "RETURNING reservation_id, reservation_status, lifecycle_sequence, expires_at",
            (result, rowNumber) ->
                new ReservationLifecycleRow(
                    result.getObject("reservation_id", UUID.class),
                    result.getString("reservation_status"),
                    result.getLong("lifecycle_sequence"),
                    result.getTimestamp("expires_at").toInstant()),
            Timestamp.from(releasedAt),
            Timestamp.from(releasedAt),
            transferId.value());
    if (!changed.isEmpty()) {
      ReservationLifecycleRow row = changed.getFirst();
      return new ReleaseResult(
          ReleaseOutcome.valueOf(row.status()),
          Optional.of(new InternalId(row.reservationId())),
          row.sequence(),
          true,
          Optional.of(row.expiresAt()));
    }
    ReservationLifecycleRow row =
        jdbc.queryForObject(
            "SELECT reservation_id, reservation_status, lifecycle_sequence, expires_at "
                + "FROM capacity.coordination_reservation WHERE transfer_id = ?",
            (result, rowNumber) ->
                new ReservationLifecycleRow(
                    result.getObject("reservation_id", UUID.class),
                    result.getString("reservation_status"),
                    result.getLong("lifecycle_sequence"),
                    result.getTimestamp("expires_at").toInstant()),
            transferId.value());
    ReleaseOutcome outcome =
        "EXPIRED".equals(row.status()) ? ReleaseOutcome.EXPIRED : ReleaseOutcome.RELEASED;
    return new ReleaseResult(
        outcome,
        Optional.of(new InternalId(row.reservationId())),
        row.sequence(),
        false,
        Optional.of(row.expiresAt()));
  }

  @Override
  public List<LifecycleCandidate> findLifecycleCandidates(Instant at, int limit) {
    return jdbc.query(
        "SELECT reservation_id, transfer_id, facility_identifier, dimension_code, unit_code, "
            + "quantity, interval_start, interval_end, expires_at, reservation_status, "
            + "lifecycle_sequence FROM capacity.coordination_reservation "
            + "WHERE reservation_status IN ('ACTIVE', 'AT_RISK') "
            + "AND (expires_at <= ? OR (reservation_status = 'ACTIVE' AND lifecycle_checked_at < ?)) "
            + "ORDER BY lifecycle_checked_at, expires_at, reservation_id LIMIT ?",
        JdbcCapacityReservationStore::lifecycleCandidate,
        Timestamp.from(at),
        Timestamp.from(at),
        limit);
  }

  @Override
  public Optional<LifecycleCandidate> lockLifecycleCandidate(InternalId reservationId) {
    return jdbc
        .query(
            "SELECT reservation_id, transfer_id, facility_identifier, dimension_code, unit_code, "
                + "quantity, interval_start, interval_end, expires_at, reservation_status, "
                + "lifecycle_sequence FROM capacity.coordination_reservation "
                + "WHERE reservation_id = ? FOR UPDATE",
            JdbcCapacityReservationStore::lifecycleCandidate,
            reservationId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<LifecycleCandidate> changeLifecycleStatus(
      InternalId reservationId,
      LifecycleStatus expected,
      LifecycleStatus next,
      Instant recordedAt) {
    return jdbc
        .query(
            "UPDATE capacity.coordination_reservation SET reservation_status = ?, "
                + "lifecycle_sequence = lifecycle_sequence + 1, status_recorded_at = ?, "
                + "lifecycle_checked_at = ? WHERE reservation_id = ? AND reservation_status = ? "
                + "RETURNING reservation_id, transfer_id, facility_identifier, dimension_code, "
                + "unit_code, quantity, interval_start, interval_end, expires_at, "
                + "reservation_status, lifecycle_sequence",
            JdbcCapacityReservationStore::lifecycleCandidate,
            next.name(),
            Timestamp.from(recordedAt),
            Timestamp.from(recordedAt),
            reservationId.value(),
            expected.name())
        .stream()
        .findFirst();
  }

  @Override
  public void recordLifecycleCheck(InternalId reservationId, Instant checkedAt) {
    jdbc.update(
        "UPDATE capacity.coordination_reservation SET lifecycle_checked_at = ? "
            + "WHERE reservation_id = ? AND reservation_status = 'ACTIVE'",
        Timestamp.from(checkedAt),
        reservationId.value());
  }

  private static LifecycleCandidate lifecycleCandidate(java.sql.ResultSet result, int rowNumber)
      throws java.sql.SQLException {
    return new LifecycleCandidate(
        new InternalId(result.getObject("reservation_id", UUID.class)),
        new InternalId(result.getObject("transfer_id", UUID.class)),
        new ExternalReference(
            new IssuerId("urn:ayuvahak:directory"),
            "Facility",
            result.getObject("facility_identifier", UUID.class).toString()),
        result.getString("dimension_code"),
        result.getString("unit_code"),
        result.getBigDecimal("quantity"),
        result.getTimestamp("interval_start").toInstant(),
        result.getTimestamp("interval_end").toInstant(),
        result.getTimestamp("expires_at").toInstant(),
        LifecycleStatus.valueOf(result.getString("reservation_status")),
        result.getLong("lifecycle_sequence"));
  }

  private static CapacityReservationResult result(
      InternalId operationId, OperationRow row, boolean replayed) {
    return new CapacityReservationResult(
        operationId,
        CapacityReservationResult.Outcome.valueOf(row.outcome()),
        Optional.ofNullable(row.reservationId()).map(InternalId::new),
        Optional.ofNullable(row.reasonCode()),
        replayed);
  }

  private record OperationRow(
      UUID transferId, String fingerprint, String outcome, UUID reservationId, String reasonCode) {}

  private record ReceiptRow(
      String fingerprint,
      UUID operationId,
      String state,
      String outcome,
      UUID reservationId,
      String reasonCode) {}

  private record ReservationLifecycleRow(
      UUID reservationId, String status, long sequence, Instant expiresAt) {}

  private record ReservationOperationLockRow(
      String outcome, UUID reservationId, UUID facilityIdentifier) {}
}
