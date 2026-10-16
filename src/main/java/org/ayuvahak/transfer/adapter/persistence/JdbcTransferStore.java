package org.ayuvahak.transfer.adapter.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.platform.idempotency.configuration.CommandIdempotencyProperties;
import org.ayuvahak.transfer.application.TransferProgressTarget;
import org.ayuvahak.transfer.application.TransferStore;
import org.ayuvahak.transfer.domain.CapacityReservationIntent;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.DestinationChangeAssessment;
import org.ayuvahak.transfer.domain.DestinationChangeReservationPlan;
import org.ayuvahak.transfer.domain.ReceivingAssessment;
import org.ayuvahak.transfer.domain.TransferDecision;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation;
import org.ayuvahak.transfer.domain.TransferDestinationChangeReconciliation;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferJourneyClosure;
import org.ayuvahak.transfer.domain.TransferProgressCorrection;
import org.ayuvahak.transfer.domain.TransferProgressFact;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for Transfer-owned Journey, first-request, and idempotency state. */
@Repository
@Lazy
public class JdbcTransferStore implements TransferStore {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String REFERRAL_COORDINATION_GUARD_TIMEOUT = "5s";
  private final JdbcTemplate jdbc;
  private final HikariDataSource dataSource;
  private final CommandIdempotencyProperties idempotencyProperties;

  public JdbcTransferStore(
      JdbcTemplate jdbc,
      HikariDataSource dataSource,
      CommandIdempotencyProperties idempotencyProperties) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "JDBC template");
    this.dataSource = java.util.Objects.requireNonNull(dataSource, "data source");
    this.idempotencyProperties =
        java.util.Objects.requireNonNull(idempotencyProperties, "idempotency properties");
  }

  @Override
  public Optional<ReceiptClaim> findReceipt(CommandReceiptKey key, RequestFingerprint fingerprint) {
    List<ReceiptClaim> claims =
        jdbc.query(
            "SELECT request_fingerprint, receipt_state, response_status, result_reference, result_version "
                + "FROM transfer.command_receipt WHERE scope_digest = ? AND idempotency_key_digest = ?",
            (result, row) -> receiptClaim(result, fingerprint),
            key.scopeDigest(),
            key.idempotencyKey().value());
    return claims.stream().findFirst();
  }

  @Override
  public ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO transfer.command_receipt (scope_digest, idempotency_key_digest, "
                + "request_fingerprint, receipt_state, recorded_at) VALUES (?, ?, ?, 'IN_PROGRESS', ?) "
                + "ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            Timestamp.from(recordedAt));
    if (inserted == 1) {
      return new ReceiptClaim(
          ReceiptState.OWNER, Optional.empty(), Optional.empty(), Optional.empty());
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, response_status, result_reference, result_version "
            + "FROM transfer.command_receipt WHERE scope_digest = ? AND idempotency_key_digest = ?",
        (result, row) -> receiptClaim(result, fingerprint),
        key.scopeDigest(),
        key.idempotencyKey().value());
  }

  @Override
  public void configureBoundedReceiptWait() {
    jdbc.execute(
        "SET LOCAL lock_timeout = '"
            + idempotencyProperties.getReceiptLockWait().toMillis()
            + "ms'");
  }

  @Override
  public void lockReferralCoordination(InternalId referralId) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        result -> {
          while (result.next()) {
            // Consume the transaction-scoped lock result.
          }
        },
        referralCoordinationLockKey(referralId));
  }

  @Override
  public ReferralCoordinationGuard acquireReferralCoordinationGuard(InternalId referralId) {
    String lockKey = referralCoordinationLockKey(referralId);
    Connection connection = null;
    boolean lockRequestMayHaveReachedServer = false;
    try {
      connection = dataSource.getConnection();
      try (Statement timeout = connection.createStatement()) {
        timeout.execute("SET lock_timeout = '" + REFERRAL_COORDINATION_GUARD_TIMEOUT + "'");
      }
      try (PreparedStatement lock =
          connection.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
        lock.setString(1, lockKey);
        lockRequestMayHaveReachedServer = true;
        try (ResultSet result = lock.executeQuery()) {
          if (!result.next()) {
            throw new SQLException("Transfer coordination lock returned no result");
          }
        }
      }
      try (Statement reset = connection.createStatement()) {
        reset.execute("RESET lock_timeout");
      }
      Connection guardedConnection = connection;
      AtomicBoolean released = new AtomicBoolean();
      return () -> {
        if (released.compareAndSet(false, true)) {
          releaseReferralCoordinationGuard(guardedConnection, lockKey);
        }
      };
    } catch (SQLException failure) {
      if (connection != null) {
        if (lockRequestMayHaveReachedServer) {
          // A failed response does not prove that PostgreSQL failed to acquire the session lock.
          // Retire the session rather than returning a possibly locked connection to Hikari.
          abortConnection(connection);
        } else {
          resetAndCloseUnheldConnection(connection);
        }
      }
      throw new DataAccessResourceFailureException(
          "Could not acquire the bounded Transfer Referral coordination guard", failure);
    }
  }

  private void releaseReferralCoordinationGuard(Connection connection, String lockKey) {
    boolean released = false;
    try (PreparedStatement unlock =
        connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
      unlock.setString(1, lockKey);
      try (ResultSet result = unlock.executeQuery()) {
        released = result.next() && result.getBoolean(1);
      }
    } catch (SQLException ignored) {
      // Evicting the session below releases any remaining session lock.
    }
    if (released) {
      closeQuietly(connection);
    } else {
      abortConnection(connection);
    }
  }

  private void resetAndCloseUnheldConnection(Connection connection) {
    try (Statement reset = connection.createStatement()) {
      reset.execute("RESET lock_timeout");
      closeQuietly(connection);
    } catch (SQLException ignored) {
      abortConnection(connection);
    }
  }

  private void abortConnection(Connection connection) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException abortFailure) {
      // A pooled close may return a session-scoped PostgreSQL advisory lock to the pool.
      // Retire the physical session instead so a failed unlock cannot strand the Referral.
    } finally {
      // JDBC abort does not return Hikari's proxy to the pool. Evict it after either abort
      // outcome so the checked-out handle is not leaked and the physical session is retired.
      dataSource.evictConnection(connection);
    }
  }

  private static void closeQuietly(Connection connection) {
    try {
      connection.close();
    } catch (SQLException ignored) {
      // Closing is best-effort after explicit unlock or abort.
    }
  }

  private static String referralCoordinationLockKey(InternalId referralId) {
    return "transfer/referral/" + java.util.Objects.requireNonNull(referralId, "Referral id");
  }

  @Override
  public void insertFirstRequest(TransferJourney journey, TransferRequestAttempt attempt) {
    jdbc.update(
        "INSERT INTO transfer.journey (journey_id, referral_id, origin_organization_id, "
            + "origin_facility_id, created_by_kind, created_by_issuer, created_by_identifier_type, "
            + "created_by_identifier, created_at, transport_required, version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        journey.id().value(),
        journey.referralId().value(),
        directoryId(journey.originOrganization()),
        journey.originFacility().map(JdbcTransferStore::directoryId).orElse(null),
        journey.createdBy().kind().name(),
        journey.createdBy().identity().issuer().value(),
        journey.createdBy().identity().resourceType(),
        journey.createdBy().identity().value(),
        Timestamp.from(journey.createdAt()),
        journey.transportRequired(),
        journey.version());
    jdbc.update(
        "INSERT INTO transfer.request_attempt (request_attempt_id, journey_id, attempt_number, "
            + "referral_id, referral_revision, recommendation_id, destination_facility_id, "
            + "request_status, requested_by_kind, requested_by_issuer, requested_by_identifier_type, "
            + "requested_by_identifier, requested_at, expires_at, version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        attempt.id().value(),
        attempt.journeyId().value(),
        attempt.attemptNumber(),
        attempt.referralId().value(),
        attempt.referralRevision(),
        attempt.recommendationId().value(),
        directoryId(attempt.destinationFacility()),
        attempt.status().name(),
        attempt.requestedBy().kind().name(),
        attempt.requestedBy().identity().issuer().value(),
        attempt.requestedBy().identity().resourceType(),
        attempt.requestedBy().identity().value(),
        Timestamp.from(attempt.requestedAt()),
        Timestamp.from(attempt.expiresAt()),
        attempt.version());
  }

  @Override
  public void completeReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId resultReference,
      long resultVersion,
      Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE transfer.command_receipt SET receipt_state = 'COMPLETED', response_status = ?, "
                + "result_reference = ?, result_version = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? AND receipt_state = 'IN_PROGRESS'",
            responseStatus,
            resultReference.value(),
            resultVersion,
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (changed != 1) {
      throw new IllegalStateException("Transfer command receipt could not be completed");
    }
  }

  @Override
  public Optional<TransferJourney> findJourney(InternalId journeyId) {
    return findJourney("journey_id", journeyId);
  }

  @Override
  public Optional<TransferJourney> lockJourney(InternalId journeyId) {
    return jdbc
        .query(
            "SELECT journey_id, referral_id, origin_organization_id, origin_facility_id, "
                + "created_by_kind, created_by_issuer, created_by_identifier_type, created_by_identifier, "
                + "created_at, transport_required, version FROM transfer.journey WHERE journey_id = ? FOR UPDATE",
            JdbcTransferStore::journey,
            journeyId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TransferJourney> findJourneyByReferral(InternalId referralId) {
    return findJourney("referral_id", referralId);
  }

  @Override
  public DestinationChangeReceiptClaim claimDestinationChangeReceipt(
      CommandReceiptKey key,
      RequestFingerprint fingerprint,
      InternalId operationId,
      Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO transfer.destination_change_receipt (scope_digest, "
                + "idempotency_key_digest, request_fingerprint, operation_id, receipt_state, recorded_at) "
                + "VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?) ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            operationId.value(),
            Timestamp.from(recordedAt));
    if (inserted == 1) {
      return new DestinationChangeReceiptClaim(ReceiptState.OWNER, Optional.of(operationId));
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, operation_id, receipt_state "
            + "FROM transfer.destination_change_receipt "
            + "WHERE scope_digest = ? AND idempotency_key_digest = ? FOR UPDATE",
        (result, row) -> {
          InternalId existing = internalId(result, "operation_id");
          if (!fingerprint.value().equals(result.getString("request_fingerprint").trim())) {
            return new DestinationChangeReceiptClaim(ReceiptState.KEY_REUSED, Optional.empty());
          }
          return new DestinationChangeReceiptClaim(
              "COMPLETED".equals(result.getString("receipt_state"))
                  ? ReceiptState.REPLAY
                  : ReceiptState.IN_PROGRESS,
              Optional.of(existing));
        },
        key.scopeDigest(),
        key.idempotencyKey().value());
  }

  @Override
  public void completeDestinationChangeReceipt(
      CommandReceiptKey key,
      RequestFingerprint fingerprint,
      InternalId operationId,
      Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE transfer.destination_change_receipt SET receipt_state = 'COMPLETED', recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? AND request_fingerprint = ? "
                + "AND operation_id = ? AND receipt_state = 'IN_PROGRESS'",
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            operationId.value());
    if (changed != 1) {
      throw new IllegalStateException("Transfer destination-change receipt could not be completed");
    }
  }

  @Override
  public boolean insertDestinationChange(TransferDestinationChangeOperation operation) {
    return jdbc.update(
            "INSERT INTO transfer.destination_change_operation (operation_id, transfer_id, "
                + "transport_request_id, old_organization_id, old_facility_id, proposed_organization_id, "
                + "proposed_facility_id, recommendation_id, referral_revision, reason_code, "
                + "capacity_dimension_code, capacity_unit_code, capacity_quantity, "
                + "capacity_interval_start, capacity_interval_end, status, "
                + "capacity_reservation_operation_id, capacity_reservation_outcome, "
                + "old_reservation_operation_id, old_hold_release_operation_id, "
                + "proposed_hold_release_operation_id, transport_operation_id, provider_outcome, "
                + "initiated_by_kind, initiated_by_issuer, initiated_by_identifier_type, "
                + "initiated_by_identifier, origin_organization_id, origin_facility_id, correlation_id, "
                + "version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ",
            operation.id().value(),
            operation.transferId().value(),
            operation.transportRequestId().value(),
            directoryId(operation.oldOrganization()),
            directoryId(operation.oldFacility()),
            directoryId(operation.proposedOrganization()),
            directoryId(operation.proposedFacility()),
            operation.recommendationId().value(),
            operation.referralRevision(),
            operation.reasonCode(),
            operation
                .reservationPlan()
                .map(DestinationChangeReservationPlan::dimensionCode)
                .orElse(null),
            operation.reservationPlan().map(DestinationChangeReservationPlan::unit).orElse(null),
            operation
                .reservationPlan()
                .map(DestinationChangeReservationPlan::quantity)
                .orElse(null),
            operation
                .reservationPlan()
                .map(DestinationChangeReservationPlan::intervalStart)
                .map(Timestamp::from)
                .orElse(null),
            operation
                .reservationPlan()
                .map(DestinationChangeReservationPlan::intervalEnd)
                .map(Timestamp::from)
                .orElse(null),
            operation.status().name(),
            operation.capacityReservationOperationId().value(),
            operation.capacityReservationOutcome().name(),
            operation.oldReservationOperationId().value(),
            operation.oldHoldReleaseOperationId().value(),
            operation.proposedHoldReleaseOperationId().value(),
            operation.transportOperationId().value(),
            operation.providerOutcome().map(Enum::name).orElse(null),
            operation.initiatedBy().kind().name(),
            operation.initiatedBy().identity().issuer().value(),
            operation.initiatedBy().identity().resourceType(),
            operation.initiatedBy().identity().value(),
            directoryId(operation.originOrganization()),
            directoryId(operation.originFacility()),
            operation.correlationId().value(),
            operation.version(),
            Timestamp.from(operation.createdAt()),
            Timestamp.from(operation.updatedAt()))
        == 1;
  }

  @Override
  public Optional<TransferDestinationChangeOperation> findDestinationChange(
      InternalId operationId) {
    return destinationChange(operationId, false);
  }

  @Override
  public Optional<ActiveDestination> findActiveDestination(InternalId transferId) {
    return jdbc
        .query(
            "SELECT organization_id, facility_id, reservation_operation_id "
                + "FROM transfer.active_destination WHERE transfer_id = ?",
            (result, row) ->
                new ActiveDestination(
                    directoryReference(
                        "Organization", result.getObject("organization_id", UUID.class)),
                    directoryReference("Facility", result.getObject("facility_id", UUID.class)),
                    internalId(result, "reservation_operation_id")),
            transferId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TransferDestinationChangeOperation> lockDestinationChange(
      InternalId operationId) {
    return destinationChange(operationId, true);
  }

  private Optional<TransferDestinationChangeOperation> destinationChange(
      InternalId operationId, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM transfer.destination_change_operation WHERE operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            JdbcTransferStore::destinationChange,
            operationId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TransferDestinationChangeOperation> findUnresolvedDestinationChange(
      InternalId transferId) {
    return jdbc
        .query(
            "SELECT * FROM transfer.destination_change_operation WHERE transfer_id = ? "
                + "AND status <> 'RESOLVED'",
            JdbcTransferStore::destinationChange,
            transferId.value())
        .stream()
        .findFirst();
  }

  @Override
  public List<InternalId> findPendingDestinationChangeReleases(int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException(
          "destination-change recovery limit must be between 1 and 500");
    }
    return jdbc.query(
        "SELECT operation_id FROM transfer.destination_change_operation "
            + "WHERE status IN ('OLD_HOLD_RELEASE_PENDING', 'PROPOSED_HOLD_RELEASE_PENDING') "
            + "ORDER BY updated_at, operation_id LIMIT ?",
        (result, row) -> new InternalId(result.getObject("operation_id", UUID.class)),
        limit);
  }

  @Override
  public boolean updateDestinationChange(
      TransferDestinationChangeOperation operation, long expectedVersion) {
    return jdbc.update(
            "UPDATE transfer.destination_change_operation SET status = ?, "
                + "capacity_reservation_outcome = ?, provider_outcome = ?, version = ?, updated_at = ? "
                + "WHERE operation_id = ? AND version = ?",
            operation.status().name(),
            operation.capacityReservationOutcome().name(),
            operation.providerOutcome().map(Enum::name).orElse(null),
            operation.version(),
            Timestamp.from(operation.updatedAt()),
            operation.id().value(),
            expectedVersion)
        == 1;
  }

  @Override
  public void insertDestinationChangeReconciliation(
      TransferDestinationChangeReconciliation reconciliation) {
    jdbc.update(
        "INSERT INTO transfer.destination_change_reconciliation (operation_id, resolution_code, "
            + "evidence_reference, occurred_at, recorded_at, recorded_by_kind, recorded_by_issuer, "
            + "recorded_by_identifier_type, recorded_by_identifier, organization_id, facility_id, "
            + "correlation_id, request_fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        reconciliation.operationId().value(),
        reconciliation.resolution().name(),
        reconciliation.evidenceReference(),
        Timestamp.from(reconciliation.occurredAt()),
        Timestamp.from(reconciliation.recordedAt()),
        reconciliation.recordedBy().kind().name(),
        reconciliation.recordedBy().identity().issuer().value(),
        reconciliation.recordedBy().identity().resourceType(),
        reconciliation.recordedBy().identity().value(),
        reconciliation.organizationId().value(),
        reconciliation.facilityId().value(),
        reconciliation.correlationId().value(),
        reconciliation.requestFingerprint());
  }

  @Override
  public void insertDestinationChangeAssessment(DestinationChangeAssessment assessment) {
    jdbc.update(
        "INSERT INTO transfer.destination_change_assessment (assessment_id, operation_id, "
            + "assessment_kind, outcome, reason_code, evidence_reference, recorded_by_kind, "
            + "recorded_by_issuer, recorded_by_identifier_type, recorded_by_identifier, authority_code, "
            + "organization_id, facility_id, occurred_at, recorded_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        assessment.id().value(),
        assessment.operationId().value(),
        assessment.kind().name(),
        assessment.outcome().name(),
        assessment.reasonCode(),
        assessment.evidenceReference().orElse(null),
        assessment.recordedBy().kind().name(),
        assessment.recordedBy().identity().issuer().value(),
        assessment.recordedBy().identity().resourceType(),
        assessment.recordedBy().identity().value(),
        assessment.kind().authorityCode(),
        directoryId(assessment.organization()),
        directoryId(assessment.facility()),
        Timestamp.from(assessment.occurredAt()),
        Timestamp.from(assessment.recordedAt()));
  }

  @Override
  public Optional<DestinationChangeAssessment> findDestinationChangeAssessment(
      InternalId operationId, ReceivingAssessment.Kind kind) {
    return jdbc
        .query(
            "SELECT * FROM transfer.destination_change_assessment "
                + "WHERE operation_id = ? AND assessment_kind = ?",
            JdbcTransferStore::destinationChangeAssessment,
            operationId.value(),
            kind.name())
        .stream()
        .findFirst();
  }

  @Override
  public boolean insertDestinationChangeDecision(
      InternalId operationId,
      String decisionKind,
      Optional<String> reasonCode,
      ActorReference recordedBy,
      ExternalReference organization,
      ExternalReference facility,
      Instant occurredAt,
      Instant recordedAt) {
    return jdbc.update(
            "INSERT INTO transfer.destination_change_decision (operation_id, decision_kind, reason_code, "
                + "recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, recorded_by_identifier, "
                + "organization_id, facility_id, occurred_at, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (operation_id) DO NOTHING",
            operationId.value(),
            decisionKind,
            reasonCode.orElse(null),
            recordedBy.kind().name(),
            recordedBy.identity().issuer().value(),
            recordedBy.identity().resourceType(),
            recordedBy.identity().value(),
            directoryId(organization),
            directoryId(facility),
            Timestamp.from(occurredAt),
            Timestamp.from(recordedAt))
        == 1;
  }

  @Override
  public boolean updateActiveDestination(
      InternalId transferId,
      ExternalReference expectedFacility,
      ExternalReference organization,
      ExternalReference facility,
      InternalId reservationOperationId,
      InternalId destinationChangeOperationId,
      Instant updatedAt) {
    return jdbc.update(
            "UPDATE transfer.active_destination SET organization_id = ?, facility_id = ?, "
                + "reservation_operation_id = ?, source_destination_change_id = ?, "
                + "version = version + 1, updated_at = ? WHERE transfer_id = ? AND facility_id = ?",
            directoryId(organization),
            directoryId(facility),
            reservationOperationId.value(),
            destinationChangeOperationId.value(),
            Timestamp.from(updatedAt),
            transferId.value(),
            directoryId(expectedFacility))
        == 1;
  }

  @Override
  public Optional<TransferJourneyClosure> findJourneyClosure(InternalId journeyId) {
    return jdbc
        .query(
            "SELECT journey_id, referral_id, reason_code, closed_by_kind, closed_by_issuer, "
                + "closed_by_identifier_type, closed_by_identifier, authority_code, organization_id, "
                + "facility_id, occurred_at, recorded_at, aggregate_sequence "
                + "FROM transfer.journey_closure WHERE journey_id = ?",
            JdbcTransferStore::journeyClosure,
            journeyId.value())
        .stream()
        .findFirst();
  }

  @Override
  public boolean canCloseJourneyUnfulfilled(InternalId journeyId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM transfer.request_attempt WHERE journey_id = ?) "
                + "AND NOT EXISTS (SELECT 1 FROM transfer.request_attempt a "
                + "LEFT JOIN transfer.transfer_record t ON t.request_attempt_id = a.request_attempt_id "
                + "LEFT JOIN transfer.capacity_reservation_request r ON r.transfer_id = t.transfer_id "
                + "WHERE a.journey_id = ? AND (a.request_status = 'PENDING' OR "
                + "(a.request_status = 'ACCEPTED' AND (t.transfer_id IS NULL "
                + "OR t.transfer_status <> 'CANCELLED' OR r.outcome IS NULL OR r.outcome NOT IN "
                + "('CONFLICT', 'INVALID', 'UNCONFIGURED', 'RELEASED', 'EXPIRED', 'NOT_RESERVED'))))) "
                + "AND NOT EXISTS (SELECT 1 FROM transfer.transfer_record t "
                + "WHERE t.journey_id = ? AND t.transfer_status <> 'CANCELLED')",
            Boolean.class,
            journeyId.value(),
            journeyId.value(),
            journeyId.value()));
  }

  @Override
  public boolean recordJourneyClosure(TransferJourneyClosure closure, long expectedJourneyVersion) {
    int changed =
        jdbc.update(
            "UPDATE transfer.journey SET version = ? WHERE journey_id = ? AND version = ?",
            closure.journeyVersion(),
            closure.journeyId().value(),
            expectedJourneyVersion);
    if (changed != 1) {
      return false;
    }
    jdbc.update(
        "INSERT INTO transfer.journey_closure (journey_id, referral_id, outcome, reason_code, "
            + "closed_by_kind, closed_by_issuer, closed_by_identifier_type, closed_by_identifier, "
            + "authority_code, organization_id, facility_id, occurred_at, recorded_at, aggregate_sequence) "
            + "VALUES (?, ?, 'UNFULFILLED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        closure.journeyId().value(),
        closure.referralId().value(),
        closure.reasonCode(),
        closure.closedBy().kind().name(),
        closure.closedBy().identity().issuer().value(),
        closure.closedBy().identity().resourceType(),
        closure.closedBy().identity().value(),
        closure.authorityCode(),
        directoryId(closure.organization()),
        closure.facility().map(JdbcTransferStore::directoryId).orElse(null),
        Timestamp.from(closure.occurredAt()),
        Timestamp.from(closure.recordedAt()),
        closure.journeyVersion());
    return true;
  }

  private Optional<TransferJourney> findJourney(String column, InternalId id) {
    List<TransferJourney> rows =
        jdbc.query(
            "SELECT journey_id, referral_id, origin_organization_id, origin_facility_id, "
                + "created_by_kind, created_by_issuer, created_by_identifier_type, created_by_identifier, "
                + "created_at, transport_required, version FROM transfer.journey WHERE "
                + column
                + " = ?",
            JdbcTransferStore::journey,
            id.value());
    return rows.stream().findFirst();
  }

  @Override
  public Optional<TransferRequestAttempt> findFirstRequest(InternalId journeyId) {
    List<TransferRequestAttempt> rows =
        jdbc.query(
            "SELECT request_attempt_id, journey_id, attempt_number, referral_id, referral_revision, "
                + "recommendation_id, destination_facility_id, request_status, requested_by_kind, "
                + "requested_by_issuer, requested_by_identifier_type, requested_by_identifier, "
                + "requested_at, expires_at, terminal_occurred_at, terminal_recorded_at, version "
                + "FROM transfer.request_attempt "
                + "WHERE journey_id = ? AND attempt_number = 1",
            JdbcTransferStore::requestAttempt,
            journeyId.value());
    return rows.stream().findFirst();
  }

  @Override
  public Optional<TransferRequestAttempt> findLatestRequest(InternalId journeyId) {
    return jdbc
        .query(
            "SELECT request_attempt_id, journey_id, attempt_number, referral_id, referral_revision, "
                + "recommendation_id, destination_facility_id, request_status, requested_by_kind, "
                + "requested_by_issuer, requested_by_identifier_type, requested_by_identifier, "
                + "requested_at, expires_at, terminal_occurred_at, terminal_recorded_at, version "
                + "FROM transfer.request_attempt WHERE journey_id = ? "
                + "ORDER BY attempt_number DESC LIMIT 1",
            JdbcTransferStore::requestAttempt,
            journeyId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TransferRequestAttempt> findRequestAttempt(
      InternalId journeyId, int attemptNumber) {
    return jdbc
        .query(
            "SELECT request_attempt_id, journey_id, attempt_number, referral_id, referral_revision, "
                + "recommendation_id, destination_facility_id, request_status, requested_by_kind, "
                + "requested_by_issuer, requested_by_identifier_type, requested_by_identifier, "
                + "requested_at, expires_at, terminal_occurred_at, terminal_recorded_at, version "
                + "FROM transfer.request_attempt WHERE journey_id = ? AND attempt_number = ?",
            JdbcTransferStore::requestAttempt,
            journeyId.value(),
            attemptNumber)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TransferAttemptState> findAttemptState(InternalId requestAttemptId) {
    return jdbc
        .query(
            "SELECT t.transfer_status, r.outcome AS reservation_outcome "
                + "FROM transfer.transfer_record t LEFT JOIN transfer.capacity_reservation_request r "
                + "ON r.transfer_id = t.transfer_id WHERE t.request_attempt_id = ?",
            (result, row) ->
                new TransferAttemptState(
                    Optional.ofNullable(result.getString("transfer_status"))
                        .map(CoordinatedTransfer.Status::valueOf),
                    Optional.ofNullable(result.getString("reservation_outcome"))
                        .map(CapacityReservationIntent.Outcome::valueOf)),
            requestAttemptId.value())
        .stream()
        .findFirst();
  }

  @Override
  public boolean insertNextRequest(
      InternalId journeyId, long expectedJourneyVersion, TransferRequestAttempt attempt) {
    int changed =
        jdbc.update(
            "UPDATE transfer.journey SET version = version + 1 "
                + "WHERE journey_id = ? AND version = ?",
            journeyId.value(),
            expectedJourneyVersion);
    if (changed != 1) {
      return false;
    }
    jdbc.update(
        "INSERT INTO transfer.request_attempt (request_attempt_id, journey_id, attempt_number, "
            + "referral_id, referral_revision, recommendation_id, destination_facility_id, "
            + "request_status, requested_by_kind, requested_by_issuer, requested_by_identifier_type, "
            + "requested_by_identifier, requested_at, expires_at, version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        attempt.id().value(),
        attempt.journeyId().value(),
        attempt.attemptNumber(),
        attempt.referralId().value(),
        attempt.referralRevision(),
        attempt.recommendationId().value(),
        directoryId(attempt.destinationFacility()),
        attempt.status().name(),
        attempt.requestedBy().kind().name(),
        attempt.requestedBy().identity().issuer().value(),
        attempt.requestedBy().identity().resourceType(),
        attempt.requestedBy().identity().value(),
        Timestamp.from(attempt.requestedAt()),
        Timestamp.from(attempt.expiresAt()),
        attempt.version());
    return true;
  }

  @Override
  public Optional<TransferRequestAttempt> findRequestAttempt(InternalId requestAttemptId) {
    List<TransferRequestAttempt> rows =
        jdbc.query(
            "SELECT request_attempt_id, journey_id, attempt_number, referral_id, referral_revision, "
                + "recommendation_id, destination_facility_id, request_status, requested_by_kind, "
                + "requested_by_issuer, requested_by_identifier_type, requested_by_identifier, "
                + "requested_at, expires_at, terminal_occurred_at, terminal_recorded_at, version "
                + "FROM transfer.request_attempt "
                + "WHERE request_attempt_id = ?",
            JdbcTransferStore::requestAttempt,
            requestAttemptId.value());
    return rows.stream().findFirst();
  }

  @Override
  public List<TransferRequestAttempt> findDueRequestAttempts(Instant dueAt, int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("expiry scan limit must be between 1 and 500");
    }
    return jdbc.query(
        "SELECT request_attempt_id, journey_id, attempt_number, referral_id, referral_revision, "
            + "recommendation_id, destination_facility_id, request_status, requested_by_kind, "
            + "requested_by_issuer, requested_by_identifier_type, requested_by_identifier, "
            + "requested_at, expires_at, terminal_occurred_at, terminal_recorded_at, version "
            + "FROM transfer.request_attempt "
            + "WHERE request_status = 'PENDING' AND expires_at <= ? "
            + "ORDER BY expires_at, request_attempt_id LIMIT ?",
        JdbcTransferStore::requestAttempt,
        Timestamp.from(dueAt),
        limit);
  }

  @Override
  public Optional<ReceivingAssessment> findLatestAssessment(
      InternalId requestAttemptId, ReceivingAssessment.Kind kind) {
    List<ReceivingAssessment> rows =
        jdbc.query(
            assessmentSelect()
                + " WHERE request_attempt_id = ? AND assessment_kind = ? "
                + "ORDER BY request_version DESC LIMIT 1",
            (result, row) -> assessment(result),
            requestAttemptId.value(),
            kind.name());
    return rows.stream().findFirst();
  }

  @Override
  public Optional<ReceivingAssessment> findAssessmentByVersion(
      InternalId requestAttemptId, ReceivingAssessment.Kind kind, long requestVersion) {
    List<ReceivingAssessment> rows =
        jdbc.query(
            assessmentSelect()
                + " WHERE request_attempt_id = ? AND assessment_kind = ? AND request_version = ?",
            (result, row) -> assessment(result),
            requestAttemptId.value(),
            kind.name(),
            requestVersion);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<DecisionResult> findDecisionByVersion(
      InternalId requestAttemptId, long requestVersion) {
    List<DecisionResult> rows =
        jdbc.query(
            "SELECT d.decision_id, d.request_attempt_id, d.decision_kind, d.reason_code, "
                + "d.transfer_id, d.recorded_by_kind, d.recorded_by_issuer, "
                + "d.recorded_by_identifier_type, d.recorded_by_identifier, d.authority_code, "
                + "d.receiving_organization_id, d.receiving_facility_id, d.occurred_at, "
                + "d.recorded_at, d.request_version, t.journey_id, t.transfer_status, "
                + "t.created_at, t.version AS transfer_version FROM transfer.request_decision d "
                + "LEFT JOIN transfer.transfer_record t ON t.transfer_id = d.transfer_id "
                + "WHERE d.request_attempt_id = ? AND d.request_version = ?",
            (result, row) -> {
              TransferDecision decision = decision(result);
              Optional<CoordinatedTransfer> transfer =
                  result.getObject("transfer_id", UUID.class) == null
                      ? Optional.empty()
                      : Optional.of(
                          new CoordinatedTransfer(
                              new InternalId(result.getObject("transfer_id", UUID.class)),
                              new InternalId(result.getObject("journey_id", UUID.class)),
                              decision.requestAttemptId(),
                              CoordinatedTransfer.Status.valueOf(
                                  result.getString("transfer_status")),
                              result.getTimestamp("created_at").toInstant(),
                              result.getLong("transfer_version")));
              return new DecisionResult(decision, transfer);
            },
            requestAttemptId.value(),
            requestVersion);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<CoordinatedTransfer> findTransfer(InternalId transferId) {
    return jdbc
        .query(
            "SELECT transfer_id, journey_id, request_attempt_id, transfer_status, created_at, version "
                + "FROM transfer.transfer_record WHERE transfer_id = ?",
            (result, row) ->
                new CoordinatedTransfer(
                    internalId(result, "transfer_id"),
                    internalId(result, "journey_id"),
                    internalId(result, "request_attempt_id"),
                    CoordinatedTransfer.Status.valueOf(result.getString("transfer_status")),
                    result.getTimestamp("created_at").toInstant(),
                    result.getLong("version")),
            transferId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TransferProgressTarget> lockProgressTarget(InternalId transferId) {
    return progressTarget(transferId, true);
  }

  @Override
  public Optional<TransferProgressTarget> findProgressTarget(InternalId transferId) {
    return progressTarget(transferId, false);
  }

  @Override
  public boolean recordTransportRequestLink(
      InternalId transferId, InternalId transportRequestId, boolean select, Instant linkedAt) {
    Optional<Boolean> existing =
        jdbc
            .query(
                "SELECT selected FROM transfer.transport_request_link "
                    + "WHERE transport_request_id = ? AND transfer_id = ?",
                (result, row) -> result.getBoolean("selected"),
                transportRequestId.value(),
                transferId.value())
            .stream()
            .findFirst();
    if (existing.isPresent()) {
      if (select && !existing.orElseThrow()) {
        jdbc.update(
            "UPDATE transfer.transport_request_link SET selected = false "
                + "WHERE transfer_id = ? AND selected",
            transferId.value());
        return jdbc.update(
                "UPDATE transfer.transport_request_link SET selected = true "
                    + "WHERE transfer_id = ? AND transport_request_id = ?",
                transferId.value(),
                transportRequestId.value())
            == 1;
      }
      return true;
    }
    if (select) {
      jdbc.update(
          "UPDATE transfer.transport_request_link SET selected = false "
              + "WHERE transfer_id = ? AND selected",
          transferId.value());
    }
    return jdbc.update(
            "INSERT INTO transfer.transport_request_link "
                + "(transport_request_id, transfer_id, selected, linked_at) VALUES (?, ?, ?, ?)",
            transportRequestId.value(),
            transferId.value(),
            select,
            Timestamp.from(linkedAt))
        == 1;
  }

  @Override
  public boolean isSelectedTransportRequest(InternalId transferId, InternalId transportRequestId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM transfer.transport_request_link "
                + "WHERE transfer_id = ? AND transport_request_id = ? AND selected)",
            Boolean.class,
            transferId.value(),
            transportRequestId.value()));
  }

  @Override
  public Optional<InternalId> findSelectedTransportRequest(InternalId transferId) {
    return jdbc.query(
        "SELECT transport_request_id FROM transfer.transport_request_link "
            + "WHERE transfer_id = ? AND selected",
        result ->
            result.next()
                ? Optional.of(internalId(result, "transport_request_id"))
                : Optional.empty(),
        transferId.value());
  }

  @Override
  public boolean recordTransportCancellationOutcome(
      InternalId transferId,
      InternalId transportRequestId,
      InternalId cancellationOperationId,
      String outcome,
      InternalId eventId,
      Instant occurredAt,
      Instant recordedAt,
      String correlationId) {
    return jdbc.update(
            "INSERT INTO transfer.transport_cancellation_outcome (cancellation_operation_id, "
                + "transfer_id, transport_request_id, outcome, event_id, occurred_at, recorded_at, correlation_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (cancellation_operation_id) DO NOTHING",
            cancellationOperationId.value(),
            transferId.value(),
            transportRequestId.value(),
            outcome,
            eventId.value(),
            Timestamp.from(occurredAt),
            Timestamp.from(recordedAt),
            correlationId)
        == 1;
  }

  @Override
  public boolean hasConfirmedTransportCancellation(
      InternalId transferId, InternalId transportRequestId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM transfer.transport_cancellation_outcome "
                + "WHERE transfer_id = ? AND transport_request_id = ? "
                + "AND outcome = 'CANCELLED_BEFORE_MOVEMENT')",
            Boolean.class,
            transferId.value(),
            transportRequestId.value()));
  }

  @Override
  public Optional<String> findTransportCancellationOutcome(
      InternalId transferId, InternalId transportRequestId) {
    return jdbc.query(
        "SELECT outcome FROM transfer.transport_cancellation_outcome "
            + "WHERE transfer_id = ? AND transport_request_id = ? "
            + "ORDER BY recorded_at DESC, cancellation_operation_id DESC LIMIT 1",
        result -> result.next() ? Optional.of(result.getString("outcome")) : Optional.empty(),
        transferId.value(),
        transportRequestId.value());
  }

  private Optional<TransferProgressTarget> progressTarget(InternalId transferId, boolean lock) {
    String sql =
        "SELECT t.transfer_id, t.journey_id, t.request_attempt_id, t.transfer_status, "
            + "t.created_at, t.version AS transfer_version, j.referral_id, "
            + "j.origin_organization_id, j.origin_facility_id, j.version AS journey_version, "
            + "ad.organization_id AS receiving_organization_id, ad.facility_id AS receiving_facility_id "
            + "FROM transfer.transfer_record t "
            + "JOIN transfer.journey j ON j.journey_id = t.journey_id "
            + "JOIN transfer.active_destination ad ON ad.transfer_id = t.transfer_id "
            + "WHERE t.transfer_id = ?"
            + (lock ? " FOR UPDATE OF t, j" : "");
    return jdbc
        .query(
            sql,
            (result, row) -> {
              InternalId journeyId = internalId(result, "journey_id");
              CoordinatedTransfer transfer =
                  new CoordinatedTransfer(
                      internalId(result, "transfer_id"),
                      journeyId,
                      internalId(result, "request_attempt_id"),
                      CoordinatedTransfer.Status.valueOf(result.getString("transfer_status")),
                      result.getTimestamp("created_at").toInstant(),
                      result.getLong("transfer_version"));
              UUID originFacilityId = result.getObject("origin_facility_id", UUID.class);
              return new TransferProgressTarget(
                  transfer,
                  directoryReference(
                      "Organization", result.getObject("origin_organization_id", UUID.class)),
                  Optional.ofNullable(originFacilityId)
                      .map(id -> directoryReference("Facility", id)),
                  directoryReference(
                      "Organization", result.getObject("receiving_organization_id", UUID.class)),
                  directoryReference(
                      "Facility", result.getObject("receiving_facility_id", UUID.class)),
                  result.getLong("journey_version"));
            },
            transferId.value())
        .stream()
        .findFirst();
  }

  @Override
  public List<TransferProgressFact> findProgressFacts(InternalId transferId) {
    return jdbc.query(
        progressFactSelect() + " WHERE transfer_id = ? ORDER BY aggregate_sequence",
        JdbcTransferStore::progressFact,
        transferId.value());
  }

  @Override
  public List<TransferProgressCorrection> findProgressCorrections(InternalId transferId) {
    return jdbc.query(
        progressCorrectionSelect() + " WHERE transfer_id = ? ORDER BY aggregate_sequence",
        JdbcTransferStore::progressCorrection,
        transferId.value());
  }

  @Override
  public Optional<TransferProgressFact> findProgressFact(
      InternalId transferId, InternalId progressFactId) {
    return jdbc
        .query(
            progressFactSelect() + " WHERE transfer_id = ? AND progress_fact_id = ?",
            JdbcTransferStore::progressFact,
            transferId.value(),
            progressFactId.value())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TransferProgressCorrection> findProgressCorrectionBySequence(
      InternalId transferId, long aggregateSequence) {
    return jdbc
        .query(
            progressCorrectionSelect() + " WHERE transfer_id = ? AND aggregate_sequence = ?",
            JdbcTransferStore::progressCorrection,
            transferId.value(),
            aggregateSequence)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TransferProgressFact> findProgressFactByVersion(
      InternalId transferId, long transferVersion) {
    return jdbc
        .query(
            progressFactSelect() + " WHERE transfer_id = ? AND transfer_version = ?",
            JdbcTransferStore::progressFact,
            transferId.value(),
            transferVersion)
        .stream()
        .findFirst();
  }

  @Override
  public boolean recordProgress(TransferProgressFact fact) {
    int changed =
        jdbc.update(
            "UPDATE transfer.transfer_record SET transfer_status = ?, version = ? "
                + "WHERE transfer_id = ? AND version = ? AND transfer_status = ?",
            fact.status().name(),
            fact.transferVersion(),
            fact.transferId().value(),
            fact.transferVersion() - 1,
            fact.previousStatus().orElseThrow().name());
    if (changed != 1) {
      return false;
    }
    int journeyChanged =
        jdbc.update(
            "UPDATE transfer.journey SET version = ? WHERE journey_id = ? AND version = ?",
            fact.aggregateSequence(),
            fact.journeyId().value(),
            fact.aggregateSequence() - 1);
    if (journeyChanged != 1) {
      return false;
    }
    jdbc.update(
        "INSERT INTO transfer.progress_fact (progress_fact_id, transfer_id, journey_id, "
            + "fact_kind, previous_status, transfer_status, exception_code, reason_code, "
            + "source_reference, recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, "
            + "recorded_by_identifier, authority_code, organization_id, facility_id, occurred_at, "
            + "recorded_at, transfer_version, aggregate_sequence) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        fact.id().value(),
        fact.transferId().value(),
        fact.journeyId().value(),
        fact.kind().name(),
        fact.previousStatus().map(Enum::name).orElse(null),
        fact.status().name(),
        fact.exceptionCode().orElse(null),
        fact.reasonCode().orElse(null),
        fact.sourceReference().orElse(null),
        fact.recordedBy().kind().name(),
        fact.recordedBy().identity().issuer().value(),
        fact.recordedBy().identity().resourceType(),
        fact.recordedBy().identity().value(),
        fact.authorityCode(),
        directoryId(fact.organization()),
        directoryId(fact.facility()),
        Timestamp.from(fact.occurredAt()),
        Timestamp.from(fact.recordedAt()),
        fact.transferVersion(),
        fact.aggregateSequence());
    return true;
  }

  @Override
  public boolean recordProgressCorrection(TransferProgressCorrection correction) {
    int journeyChanged =
        jdbc.update(
            "UPDATE transfer.journey SET version = ? WHERE journey_id = ? AND version = ?",
            correction.aggregateSequence(),
            correction.journeyId().value(),
            correction.aggregateSequence() - 1);
    if (journeyChanged != 1) {
      return false;
    }
    jdbc.update(
        "INSERT INTO transfer.progress_correction (correction_id, transfer_id, journey_id, "
            + "corrects_progress_fact_id, correction_kind, corrected_occurred_at, "
            + "corrected_result_code, reason_code, recorded_by_kind, recorded_by_issuer, "
            + "recorded_by_identifier_type, recorded_by_identifier, authority_code, "
            + "organization_id, facility_id, recorded_at, transfer_version, aggregate_sequence) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        correction.id().value(),
        correction.transferId().value(),
        correction.journeyId().value(),
        correction.correctsProgressFactId().value(),
        correction.kind().name(),
        correction.correctedOccurredAt().map(Timestamp::from).orElse(null),
        correction.correctedResultCode().orElse(null),
        correction.reasonCode(),
        correction.recordedBy().kind().name(),
        correction.recordedBy().identity().issuer().value(),
        correction.recordedBy().identity().resourceType(),
        correction.recordedBy().identity().value(),
        correction.authorityCode(),
        directoryId(correction.organization()),
        directoryId(correction.facility()),
        Timestamp.from(correction.recordedAt()),
        correction.transferVersion(),
        correction.aggregateSequence());
    return true;
  }

  @Override
  public Optional<ReceiptClaim> findProgressReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint) {
    return jdbc
        .query(
            "SELECT request_fingerprint, receipt_state, response_status, result_reference, "
                + "result_version FROM transfer.progress_command_receipt "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ?",
            (result, row) -> receiptClaim(result, fingerprint),
            key.scopeDigest(),
            key.idempotencyKey().value())
        .stream()
        .findFirst();
  }

  @Override
  public ReceiptClaim claimProgressReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
    int inserted =
        jdbc.update(
            "INSERT INTO transfer.progress_command_receipt (scope_digest, idempotency_key_digest, "
                + "request_fingerprint, receipt_state, recorded_at) VALUES (?, ?, ?, 'IN_PROGRESS', ?) "
                + "ON CONFLICT DO NOTHING",
            key.scopeDigest(),
            key.idempotencyKey().value(),
            fingerprint.value(),
            Timestamp.from(recordedAt));
    if (inserted == 1) {
      return new ReceiptClaim(
          ReceiptState.OWNER, Optional.empty(), Optional.empty(), Optional.empty());
    }
    return jdbc.queryForObject(
        "SELECT request_fingerprint, receipt_state, response_status, result_reference, "
            + "result_version FROM transfer.progress_command_receipt "
            + "WHERE scope_digest = ? AND idempotency_key_digest = ?",
        (result, row) -> receiptClaim(result, fingerprint),
        key.scopeDigest(),
        key.idempotencyKey().value());
  }

  @Override
  public void completeProgressReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId transferId,
      long resultVersion,
      Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE transfer.progress_command_receipt SET receipt_state = 'COMPLETED', "
                + "response_status = ?, result_reference = ?, result_version = ?, recorded_at = ? "
                + "WHERE scope_digest = ? AND idempotency_key_digest = ? "
                + "AND receipt_state = 'IN_PROGRESS'",
            responseStatus,
            transferId.value(),
            resultVersion,
            Timestamp.from(recordedAt),
            key.scopeDigest(),
            key.idempotencyKey().value());
    if (changed != 1) {
      throw new IllegalStateException("Transfer progress receipt could not be completed");
    }
  }

  @Override
  public Optional<CapacityReservationIntent> findReservationIntent(InternalId transferId) {
    return jdbc
        .query(
            reservationIntentSelect()
                + " JOIN transfer.active_destination ad ON ad.transfer_id = r.transfer_id "
                + "AND ad.reservation_operation_id = r.operation_id WHERE r.transfer_id = ?",
            JdbcTransferStore::reservationIntent,
            transferId.value())
        .stream()
        .findFirst();
  }

  @Override
  public List<CapacityReservationIntent> findDueReservationIntents(Instant dueAt, int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException(
          "reservation recovery scan limit must be between 1 and 500");
    }
    return jdbc.query(
        reservationIntentSelect()
            + " JOIN transfer.active_destination ad ON ad.transfer_id = r.transfer_id "
            + "AND ad.reservation_operation_id = r.operation_id "
            + "WHERE r.outcome IN ('PENDING', 'IN_PROGRESS', 'UNAVAILABLE', 'UNKNOWN', 'RESERVED') "
            + "AND next_attempt_at <= ? "
            + "ORDER BY r.next_attempt_at, r.transfer_id LIMIT ?",
        JdbcTransferStore::reservationIntent,
        Timestamp.from(dueAt),
        limit);
  }

  @Override
  public boolean claimReservationAttempt(
      InternalId transferId,
      int expectedAttemptCount,
      int claimedAttemptCount,
      Instant leaseUntil,
      Instant claimedAt) {
    if (expectedAttemptCount < 0 || claimedAttemptCount != Math.addExact(expectedAttemptCount, 1)) {
      throw new IllegalArgumentException("reservation attempt claim must increment once");
    }
    if (!leaseUntil.isAfter(claimedAt)) {
      throw new IllegalArgumentException("reservation attempt lease must be in the future");
    }
    return jdbc.update(
            "UPDATE transfer.capacity_reservation_request r SET outcome = 'IN_PROGRESS', "
                + "reservation_id = NULL, reason_code = NULL, attempt_count = ?, "
                + "next_attempt_at = ?, recorded_at = ? "
                + "WHERE transfer_id = ? AND attempt_count = ? "
                + "AND outcome IN ('PENDING', 'IN_PROGRESS', 'UNAVAILABLE', 'UNKNOWN') "
                + "AND next_attempt_at <= ? AND EXISTS (SELECT 1 FROM transfer.transfer_record t "
                + "WHERE t.transfer_id = r.transfer_id AND transfer_status IN "
                + "('AWAITING_RESERVATION', 'CANCELLATION_PENDING_RELEASE', 'ARRIVED'))",
            claimedAttemptCount,
            Timestamp.from(leaseUntil),
            Timestamp.from(claimedAt),
            transferId.value(),
            expectedAttemptCount,
            Timestamp.from(claimedAt))
        == 1;
  }

  @Override
  public boolean claimReservationOutcomeCheck(
      InternalId transferId,
      int expectedAttemptCount,
      int claimedAttemptCount,
      Instant leaseUntil,
      Instant claimedAt) {
    if (expectedAttemptCount < 0 || claimedAttemptCount != Math.addExact(expectedAttemptCount, 1)) {
      throw new IllegalArgumentException("reservation outcome claim must increment once");
    }
    if (!leaseUntil.isAfter(claimedAt)) {
      throw new IllegalArgumentException("reservation outcome lease must be in the future");
    }
    return jdbc.update(
            "UPDATE transfer.capacity_reservation_request r SET attempt_count = ?, "
                + "next_attempt_at = ?, recorded_at = ? WHERE transfer_id = ? "
                + "AND attempt_count = ? AND outcome = 'RESERVED' AND next_attempt_at <= ? "
                + "AND EXISTS (SELECT 1 FROM transfer.transfer_record t "
                + "WHERE t.transfer_id = r.transfer_id AND transfer_status IN "
                + "('AWAITING_RESERVATION', 'READY', 'ARRIVED'))",
            claimedAttemptCount,
            Timestamp.from(leaseUntil),
            Timestamp.from(claimedAt),
            transferId.value(),
            expectedAttemptCount,
            Timestamp.from(claimedAt))
        == 1;
  }

  @Override
  public boolean deferReservationOutcomeCheck(
      InternalId transferId, int expectedAttemptCount, Instant nextAttemptAt, Instant recordedAt) {
    return jdbc.update(
            "UPDATE transfer.capacity_reservation_request SET next_attempt_at = ?, recorded_at = ? "
                + "WHERE transfer_id = ? AND attempt_count = ? AND outcome = 'RESERVED'",
            Timestamp.from(nextAttemptAt),
            Timestamp.from(recordedAt),
            transferId.value(),
            expectedAttemptCount)
        == 1;
  }

  @Override
  public List<InternalId> findPendingCancellationTransfers(int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException(
          "cancellation recovery scan limit must be between 1 and 500");
    }
    return jdbc.query(
        "SELECT transfer_id FROM transfer.transfer_record "
            + "WHERE transfer_status = 'CANCELLATION_PENDING_RELEASE' "
            + "ORDER BY created_at, transfer_id LIMIT ?",
        (result, row) -> new InternalId(result.getObject("transfer_id", UUID.class)),
        limit);
  }

  @Override
  public boolean claimConsumerEvent(InternalId eventId, EventType eventType, Instant recordedAt) {
    return jdbc.update(
            "INSERT INTO transfer.consumer_event_receipt "
                + "(event_id, event_type, event_state, recorded_at) "
                + "VALUES (?, ?, 'PROCESSING', ?) ON CONFLICT (event_id) DO NOTHING",
            eventId.value(),
            eventType.toString(),
            Timestamp.from(recordedAt))
        == 1;
  }

  @Override
  public boolean isConsumerEventCompleted(InternalId eventId, EventType eventType) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM transfer.consumer_event_receipt "
                + "WHERE event_id = ? AND event_type = ? AND event_state = 'COMPLETED')",
            Boolean.class,
            eventId.value(),
            eventType.toString()));
  }

  @Override
  public void completeConsumerEvent(
      InternalId eventId, Optional<InternalId> transferId, String outcome, Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE transfer.consumer_event_receipt SET event_state = 'COMPLETED', "
                + "transfer_id = ?, outcome = ?, recorded_at = ? "
                + "WHERE event_id = ? AND event_state = 'PROCESSING'",
            transferId.map(InternalId::value).orElse(null),
            outcome,
            Timestamp.from(recordedAt),
            eventId.value());
    if (changed != 1) {
      throw new IllegalStateException("Transfer event consumer receipt could not be completed");
    }
  }

  @Override
  public boolean advanceCapacityEventSequence(InternalId transferId, long sequence) {
    if (sequence < 1) {
      throw new IllegalArgumentException("Capacity reservation event sequence must be positive");
    }
    return jdbc.update(
            "UPDATE transfer.capacity_reservation_request SET last_capacity_event_sequence = ? "
                + "WHERE transfer_id = ? AND last_capacity_event_sequence < ?",
            sequence,
            transferId.value(),
            sequence)
        == 1;
  }

  @Override
  public boolean recordReservationLifecycle(
      InternalId transferId,
      long sequence,
      CapacityReservationIntent.Outcome outcome,
      Optional<String> reasonCode) {
    if (sequence < 0) {
      throw new IllegalArgumentException("Capacity reservation sequence cannot be negative");
    }
    return jdbc.update(
            "UPDATE transfer.capacity_reservation_request SET outcome = ?, reason_code = ?, "
                + "last_capacity_event_sequence = GREATEST(last_capacity_event_sequence, ?) "
                + "WHERE transfer_id = ? AND last_capacity_event_sequence <= ?",
            outcome.name(),
            reasonCode.orElse(null),
            sequence,
            transferId.value(),
            sequence)
        == 1;
  }

  @Override
  public boolean recordReservationOutcome(
      InternalId transferId,
      int expectedAttemptCount,
      CapacityReservationIntent.Outcome outcome,
      Optional<InternalId> reservationId,
      Optional<String> reasonCode,
      boolean mayMarkReady,
      Instant nextAttemptAt,
      Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE transfer.capacity_reservation_request SET outcome = ?, reservation_id = ?, "
                + "reason_code = ?, next_attempt_at = ?, recorded_at = ? "
                + "WHERE transfer_id = ? AND outcome IN ('IN_PROGRESS', 'RESERVED') "
                + "AND attempt_count = ?",
            outcome.name(),
            reservationId.map(InternalId::value).orElse(null),
            reasonCode.orElse(null),
            Timestamp.from(nextAttemptAt),
            Timestamp.from(recordedAt),
            transferId.value(),
            expectedAttemptCount);
    if (changed == 0) {
      return false;
    }
    if (outcome == CapacityReservationIntent.Outcome.RESERVED && mayMarkReady) {
      // An actual Arrival may be recorded before a delayed reservation response is reconciled.
      // Preserve that later factual state; a reservation result must never move it back to READY.
      jdbc.update(
          "UPDATE transfer.transfer_record SET transfer_status = 'READY', version = version + 1 "
              + "WHERE transfer_id = ? AND transfer_status = 'AWAITING_RESERVATION'",
          transferId.value());
    }
    return true;
  }

  @Override
  public boolean advancePendingRequest(
      InternalId requestAttemptId, long expectedVersion, String status, Instant now) {
    int changed =
        jdbc.update(
            "UPDATE transfer.request_attempt SET request_status = ?, version = version + 1 "
                + "WHERE request_attempt_id = ? AND request_status = 'PENDING' AND version = ? "
                + "AND expires_at > ?",
            status,
            requestAttemptId.value(),
            expectedVersion,
            Timestamp.from(now));
    return changed == 1;
  }

  @Override
  public boolean withdrawPendingRequest(
      InternalId requestAttemptId, long expectedVersion, Instant occurredAt, Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE transfer.request_attempt SET request_status = 'WITHDRAWN', "
                + "terminal_occurred_at = ?, terminal_recorded_at = ?, version = version + 1 "
                + "WHERE request_attempt_id = ? AND request_status = 'PENDING' AND version = ? "
                + "AND expires_at > ?",
            Timestamp.from(occurredAt),
            Timestamp.from(recordedAt),
            requestAttemptId.value(),
            expectedVersion,
            Timestamp.from(recordedAt));
    return changed == 1;
  }

  @Override
  public boolean expirePendingRequest(
      InternalId requestAttemptId, long expectedVersion, Instant recordedAt) {
    int changed =
        jdbc.update(
            "UPDATE transfer.request_attempt SET request_status = 'EXPIRED', "
                + "terminal_occurred_at = expires_at, terminal_recorded_at = ?, version = version + 1 "
                + "WHERE request_attempt_id = ? AND request_status = 'PENDING' AND version = ? "
                + "AND expires_at <= ?",
            Timestamp.from(recordedAt),
            requestAttemptId.value(),
            expectedVersion,
            Timestamp.from(recordedAt));
    return changed == 1;
  }

  @Override
  public void insertAssessment(ReceivingAssessment assessment) {
    jdbc.update(
        "INSERT INTO transfer.receiving_assessment (assessment_id, request_attempt_id, "
            + "assessment_kind, outcome, reason_code, evidence_reference, recorded_by_kind, "
            + "recorded_by_issuer, recorded_by_identifier_type, recorded_by_identifier, "
            + "authority_code, receiving_organization_id, receiving_facility_id, occurred_at, "
            + "recorded_at, request_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        assessment.id().value(),
        assessment.requestAttemptId().value(),
        assessment.kind().name(),
        assessment.outcome().name(),
        assessment.reasonCode(),
        assessment.evidenceReference().orElse(null),
        assessment.recordedBy().kind().name(),
        assessment.recordedBy().identity().issuer().value(),
        assessment.recordedBy().identity().resourceType(),
        assessment.recordedBy().identity().value(),
        assessment.authorityCode(),
        UUID.fromString(assessment.receivingOrganization().value()),
        UUID.fromString(assessment.receivingFacility().value()),
        Timestamp.from(assessment.occurredAt()),
        Timestamp.from(assessment.recordedAt()),
        assessment.requestVersion());
  }

  @Override
  public void insertDecisionAndOptionalTransfer(
      TransferDecision decision,
      Optional<CoordinatedTransfer> transfer,
      Optional<CapacityReservationIntent> reservationIntent) {
    transfer.ifPresent(
        value ->
            jdbc.update(
                "INSERT INTO transfer.transfer_record (transfer_id, journey_id, request_attempt_id, "
                    + "transfer_status, created_at, version) VALUES (?, ?, ?, ?, ?, ?)",
                value.id().value(),
                value.journeyId().value(),
                value.requestAttemptId().value(),
                value.status().name(),
                Timestamp.from(value.createdAt()),
                value.version()));
    if (transfer.isPresent() != reservationIntent.isPresent()) {
      throw new IllegalArgumentException(
          "accepted Transfer and reservation intent must be persisted together");
    }
    reservationIntent.ifPresent(this::insertReservationIntent);
    jdbc.update(
        "INSERT INTO transfer.request_decision (decision_id, request_attempt_id, decision_kind, "
            + "reason_code, transfer_id, recorded_by_kind, recorded_by_issuer, "
            + "recorded_by_identifier_type, recorded_by_identifier, authority_code, "
            + "receiving_organization_id, receiving_facility_id, occurred_at, recorded_at, "
            + "request_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        decision.id().value(),
        decision.requestAttemptId().value(),
        decision.kind().name(),
        decision.reasonCode().orElse(null),
        decision.transferId().map(InternalId::value).orElse(null),
        decision.recordedBy().kind().name(),
        decision.recordedBy().identity().issuer().value(),
        decision.recordedBy().identity().resourceType(),
        decision.recordedBy().identity().value(),
        decision.authorityCode(),
        UUID.fromString(decision.receivingOrganization().value()),
        UUID.fromString(decision.receivingFacility().value()),
        Timestamp.from(decision.occurredAt()),
        Timestamp.from(decision.recordedAt()),
        decision.requestVersion());
    transfer.ifPresent(
        value ->
            jdbc.update(
                "INSERT INTO transfer.active_destination (transfer_id, organization_id, facility_id, "
                    + "reservation_operation_id, source_destination_change_id, version, updated_at) "
                    + "VALUES (?, ?, ?, ?, NULL, 1, ?)",
                value.id().value(),
                UUID.fromString(decision.receivingOrganization().value()),
                UUID.fromString(decision.receivingFacility().value()),
                value.id().value(),
                Timestamp.from(decision.recordedAt())));
  }

  private void insertReservationIntent(CapacityReservationIntent intent) {
    jdbc.update(
        "INSERT INTO transfer.capacity_reservation_request (operation_id, transfer_id, "
            + "organization_issuer, organization_identifier_type, organization_identifier, "
            + "facility_issuer, facility_identifier_type, facility_identifier, dimension_code, unit_code, "
            + "quantity, interval_start, interval_end, expires_at, outcome, reservation_id, reason_code, "
            + "attempt_count, next_attempt_at, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        intent.operationId().value(),
        intent.transferId().value(),
        intent.organization().issuer().value(),
        intent.organization().resourceType(),
        UUID.fromString(intent.organization().value()),
        intent.facility().issuer().value(),
        intent.facility().resourceType(),
        UUID.fromString(intent.facility().value()),
        intent.dimensionCode().orElse(null),
        intent.unit().orElse(null),
        intent.quantity().orElse(null),
        intent.intervalStart().map(Timestamp::from).orElse(null),
        intent.intervalEnd().map(Timestamp::from).orElse(null),
        intent.expiresAt().map(Timestamp::from).orElse(null),
        intent.outcome().name(),
        intent.reservationId().map(InternalId::value).orElse(null),
        intent.reasonCode().orElse(null),
        intent.attemptCount(),
        Timestamp.from(intent.nextAttemptAt()),
        Timestamp.from(intent.nextAttemptAt()));
  }

  private static String reservationIntentSelect() {
    return "SELECT r.operation_id, r.transfer_id, r.organization_identifier, r.facility_identifier, "
        + "r.dimension_code, r.unit_code, r.quantity, r.interval_start, r.interval_end, r.expires_at, "
        + "r.outcome, r.reservation_id, r.reason_code, r.attempt_count, r.next_attempt_at "
        + "FROM transfer.capacity_reservation_request r";
  }

  private static CapacityReservationIntent reservationIntent(ResultSet result, int row)
      throws SQLException {
    return new CapacityReservationIntent(
        internalId(result, "operation_id"),
        internalId(result, "transfer_id"),
        directoryReference("Organization", result.getObject("organization_identifier", UUID.class)),
        directoryReference("Facility", result.getObject("facility_identifier", UUID.class)),
        Optional.ofNullable(result.getString("dimension_code")),
        Optional.ofNullable(result.getString("unit_code")),
        Optional.ofNullable(result.getBigDecimal("quantity")),
        Optional.ofNullable(result.getTimestamp("interval_start")).map(Timestamp::toInstant),
        Optional.ofNullable(result.getTimestamp("interval_end")).map(Timestamp::toInstant),
        Optional.ofNullable(result.getTimestamp("expires_at")).map(Timestamp::toInstant),
        CapacityReservationIntent.Outcome.valueOf(result.getString("outcome")),
        Optional.ofNullable(result.getObject("reservation_id", UUID.class)).map(InternalId::new),
        Optional.ofNullable(result.getString("reason_code")),
        result.getInt("attempt_count"),
        result.getTimestamp("next_attempt_at").toInstant());
  }

  private static String assessmentSelect() {
    return "SELECT assessment_id, request_attempt_id, assessment_kind, outcome, reason_code, "
        + "evidence_reference, recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, "
        + "recorded_by_identifier, authority_code, receiving_organization_id, receiving_facility_id, "
        + "occurred_at, recorded_at, request_version FROM transfer.receiving_assessment";
  }

  private static ReceivingAssessment assessment(ResultSet result) throws SQLException {
    return new ReceivingAssessment(
        internalId(result, "assessment_id"),
        internalId(result, "request_attempt_id"),
        ReceivingAssessment.Kind.valueOf(result.getString("assessment_kind")),
        ReceivingAssessment.Outcome.valueOf(result.getString("outcome")),
        result.getString("reason_code"),
        Optional.ofNullable(result.getString("evidence_reference")),
        actor(result, "recorded_by"),
        result.getString("authority_code"),
        directoryReference(
            "Organization", result.getObject("receiving_organization_id", UUID.class)),
        directoryReference("Facility", result.getObject("receiving_facility_id", UUID.class)),
        result.getTimestamp("occurred_at").toInstant(),
        result.getTimestamp("recorded_at").toInstant(),
        result.getLong("request_version"));
  }

  private static TransferDecision decision(ResultSet result) throws SQLException {
    return new TransferDecision(
        internalId(result, "decision_id"),
        internalId(result, "request_attempt_id"),
        TransferDecision.Kind.valueOf(result.getString("decision_kind")),
        Optional.ofNullable(result.getString("reason_code")),
        Optional.ofNullable(result.getObject("transfer_id", UUID.class)).map(InternalId::new),
        actor(result, "recorded_by"),
        result.getString("authority_code"),
        directoryReference(
            "Organization", result.getObject("receiving_organization_id", UUID.class)),
        directoryReference("Facility", result.getObject("receiving_facility_id", UUID.class)),
        result.getTimestamp("occurred_at").toInstant(),
        result.getTimestamp("recorded_at").toInstant(),
        result.getLong("request_version"));
  }

  private static ReceiptClaim receiptClaim(ResultSet result, RequestFingerprint fingerprint)
      throws SQLException {
    if (!fingerprint.value().equals(result.getString("request_fingerprint"))) {
      return new ReceiptClaim(
          ReceiptState.KEY_REUSED, Optional.empty(), Optional.empty(), Optional.empty());
    }
    if ("IN_PROGRESS".equals(result.getString("receipt_state"))) {
      return new ReceiptClaim(
          ReceiptState.IN_PROGRESS, Optional.empty(), Optional.empty(), Optional.empty());
    }
    return new ReceiptClaim(
        ReceiptState.REPLAY,
        Optional.of(result.getInt("response_status")),
        Optional.of(new InternalId(result.getObject("result_reference", UUID.class))),
        Optional.of(result.getLong("result_version")));
  }

  private static String progressFactSelect() {
    return "SELECT progress_fact_id, transfer_id, journey_id, fact_kind, previous_status, "
        + "transfer_status, exception_code, reason_code, source_reference, recorded_by_kind, "
        + "recorded_by_issuer, recorded_by_identifier_type, recorded_by_identifier, authority_code, "
        + "organization_id, facility_id, occurred_at, recorded_at, transfer_version, aggregate_sequence "
        + "FROM transfer.progress_fact";
  }

  private static TransferProgressFact progressFact(ResultSet result, int row) throws SQLException {
    String previousStatus = result.getString("previous_status");
    return new TransferProgressFact(
        internalId(result, "progress_fact_id"),
        internalId(result, "transfer_id"),
        internalId(result, "journey_id"),
        TransferProgressFact.Kind.valueOf(result.getString("fact_kind")),
        Optional.ofNullable(previousStatus).map(CoordinatedTransfer.Status::valueOf),
        CoordinatedTransfer.Status.valueOf(result.getString("transfer_status")),
        Optional.ofNullable(result.getString("exception_code")),
        Optional.ofNullable(result.getString("reason_code")),
        Optional.ofNullable(result.getString("source_reference")),
        actor(result, "recorded_by"),
        result.getString("authority_code"),
        directoryReference("Organization", result.getObject("organization_id", UUID.class)),
        directoryReference("Facility", result.getObject("facility_id", UUID.class)),
        result.getTimestamp("occurred_at").toInstant(),
        result.getTimestamp("recorded_at").toInstant(),
        result.getLong("transfer_version"),
        result.getLong("aggregate_sequence"));
  }

  private static String progressCorrectionSelect() {
    return "SELECT correction_id, transfer_id, journey_id, corrects_progress_fact_id, "
        + "correction_kind, corrected_occurred_at, corrected_result_code, reason_code, "
        + "recorded_by_kind, recorded_by_issuer, recorded_by_identifier_type, "
        + "recorded_by_identifier, authority_code, organization_id, facility_id, recorded_at, "
        + "transfer_version, aggregate_sequence FROM transfer.progress_correction";
  }

  private static TransferProgressCorrection progressCorrection(ResultSet result, int row)
      throws SQLException {
    return new TransferProgressCorrection(
        internalId(result, "correction_id"),
        internalId(result, "transfer_id"),
        internalId(result, "journey_id"),
        internalId(result, "corrects_progress_fact_id"),
        TransferProgressCorrection.Kind.valueOf(result.getString("correction_kind")),
        Optional.ofNullable(result.getTimestamp("corrected_occurred_at")).map(Timestamp::toInstant),
        Optional.ofNullable(result.getString("corrected_result_code")),
        result.getString("reason_code"),
        actor(result, "recorded_by"),
        result.getString("authority_code"),
        directoryReference("Organization", result.getObject("organization_id", UUID.class)),
        directoryReference("Facility", result.getObject("facility_id", UUID.class)),
        result.getTimestamp("recorded_at").toInstant(),
        result.getLong("transfer_version"),
        result.getLong("aggregate_sequence"));
  }

  private static TransferJourney journey(ResultSet result, int row) throws SQLException {
    UUID facility = result.getObject("origin_facility_id", UUID.class);
    return new TransferJourney(
        internalId(result, "journey_id"),
        internalId(result, "referral_id"),
        directoryReference("Organization", result.getObject("origin_organization_id", UUID.class)),
        Optional.ofNullable(facility).map(id -> directoryReference("Facility", id)),
        actor(result, "created_by"),
        result.getTimestamp("created_at").toInstant(),
        result.getBoolean("transport_required"),
        result.getLong("version"));
  }

  private static TransferJourneyClosure journeyClosure(ResultSet result, int row)
      throws SQLException {
    return new TransferJourneyClosure(
        internalId(result, "journey_id"),
        internalId(result, "referral_id"),
        result.getString("reason_code"),
        actor(result, "closed_by"),
        result.getString("authority_code"),
        directoryReference("Organization", result.getObject("organization_id", UUID.class)),
        Optional.ofNullable(result.getObject("facility_id", UUID.class))
            .map(id -> directoryReference("Facility", id)),
        result.getTimestamp("occurred_at").toInstant(),
        result.getTimestamp("recorded_at").toInstant(),
        result.getLong("aggregate_sequence"));
  }

  private static TransferDestinationChangeOperation destinationChange(ResultSet result, int row)
      throws SQLException {
    return new TransferDestinationChangeOperation(
        internalId(result, "operation_id"),
        internalId(result, "transfer_id"),
        internalId(result, "transport_request_id"),
        directoryReference("Organization", result.getObject("old_organization_id", UUID.class)),
        directoryReference("Facility", result.getObject("old_facility_id", UUID.class)),
        directoryReference(
            "Organization", result.getObject("proposed_organization_id", UUID.class)),
        directoryReference("Facility", result.getObject("proposed_facility_id", UUID.class)),
        internalId(result, "recommendation_id"),
        result.getLong("referral_revision"),
        result.getString("reason_code"),
        reservationPlan(result),
        TransferDestinationChangeOperation.Status.valueOf(result.getString("status")),
        internalId(result, "capacity_reservation_operation_id"),
        TransferDestinationChangeOperation.CapacityOutcome.valueOf(
            result.getString("capacity_reservation_outcome")),
        internalId(result, "old_reservation_operation_id"),
        internalId(result, "old_hold_release_operation_id"),
        internalId(result, "proposed_hold_release_operation_id"),
        internalId(result, "transport_operation_id"),
        Optional.ofNullable(result.getString("provider_outcome"))
            .map(TransferDestinationChangeOperation.ProviderOutcome::valueOf),
        actor(result, "initiated_by"),
        directoryReference("Organization", result.getObject("origin_organization_id", UUID.class)),
        directoryReference("Facility", result.getObject("origin_facility_id", UUID.class)),
        new CorrelationId(result.getString("correlation_id")),
        result.getLong("version"),
        result.getTimestamp("created_at").toInstant(),
        result.getTimestamp("updated_at").toInstant());
  }

  private static Optional<DestinationChangeReservationPlan> reservationPlan(ResultSet result)
      throws SQLException {
    String dimension = result.getString("capacity_dimension_code");
    if (dimension == null) {
      return Optional.empty();
    }
    return Optional.of(
        new DestinationChangeReservationPlan(
            dimension,
            result.getString("capacity_unit_code"),
            result.getBigDecimal("capacity_quantity"),
            result.getTimestamp("capacity_interval_start").toInstant(),
            result.getTimestamp("capacity_interval_end").toInstant()));
  }

  private static DestinationChangeAssessment destinationChangeAssessment(ResultSet result, int row)
      throws SQLException {
    return new DestinationChangeAssessment(
        internalId(result, "assessment_id"),
        internalId(result, "operation_id"),
        ReceivingAssessment.Kind.valueOf(result.getString("assessment_kind")),
        ReceivingAssessment.Outcome.valueOf(result.getString("outcome")),
        result.getString("reason_code"),
        Optional.ofNullable(result.getString("evidence_reference")),
        actor(result, "recorded_by"),
        directoryReference("Organization", result.getObject("organization_id", UUID.class)),
        directoryReference("Facility", result.getObject("facility_id", UUID.class)),
        result.getTimestamp("occurred_at").toInstant(),
        result.getTimestamp("recorded_at").toInstant());
  }

  private static TransferRequestAttempt requestAttempt(ResultSet result, int row)
      throws SQLException {
    return new TransferRequestAttempt(
        internalId(result, "request_attempt_id"),
        internalId(result, "journey_id"),
        result.getInt("attempt_number"),
        internalId(result, "referral_id"),
        result.getLong("referral_revision"),
        internalId(result, "recommendation_id"),
        directoryReference("Facility", result.getObject("destination_facility_id", UUID.class)),
        TransferRequestAttempt.Status.valueOf(result.getString("request_status")),
        actor(result, "requested_by"),
        result.getTimestamp("requested_at").toInstant(),
        result.getTimestamp("expires_at").toInstant(),
        Optional.ofNullable(result.getTimestamp("terminal_occurred_at")).map(Timestamp::toInstant),
        Optional.ofNullable(result.getTimestamp("terminal_recorded_at")).map(Timestamp::toInstant),
        result.getLong("version"));
  }

  private static InternalId internalId(ResultSet result, String column) throws SQLException {
    return new InternalId(result.getObject(column, UUID.class));
  }

  private static ActorReference actor(ResultSet result, String prefix) throws SQLException {
    return new ActorReference(
        ActorKind.valueOf(result.getString(prefix + "_kind")),
        new ExternalReference(
            new IssuerId(result.getString(prefix + "_issuer")),
            result.getString(prefix + "_identifier_type"),
            result.getString(prefix + "_identifier")));
  }

  private static UUID directoryId(ExternalReference reference) {
    if (!DIRECTORY_ISSUER.equals(reference.issuer().value())) {
      throw new IllegalArgumentException("Transfer Directory reference has an unexpected issuer");
    }
    return UUID.fromString(reference.value());
  }

  private static ExternalReference directoryReference(String type, UUID id) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), type, id.toString());
  }
}
