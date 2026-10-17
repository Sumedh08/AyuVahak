package org.ayuvahak.capacity.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityReservationIntent;
import org.ayuvahak.capacity.domain.CapacityReservationOutcome;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.capacity.domain.CapacityReservationSubject;
import org.ayuvahak.capacity.domain.FederatedReceivingCapacityReservationOutcome;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;

/** Capacity-owned persistence boundary for coordination holds and stable operation outcomes. */
public interface CapacityReservationStore {

  ReceiptClaim claimReceipt(
      CommandReceiptKey key,
      RequestFingerprint fingerprint,
      InternalId operationId,
      Instant recordedAt);

  void completeReceipt(
      CommandReceiptKey key,
      RequestFingerprint fingerprint,
      CapacityReservationResult result,
      Instant recordedAt);

  OperationClaim lockOperation(
      InternalId operationId,
      CapacityReservationSubject subject,
      RequestFingerprint fingerprint,
      Instant recordedAt);

  default OperationClaim lockOperation(
      InternalId operationId,
      InternalId transferId,
      RequestFingerprint fingerprint,
      Instant recordedAt) {
    return lockOperation(
        operationId, CapacityReservationSubject.transfer(transferId), fingerprint, recordedAt);
  }

  BigDecimal sumActiveReservations(
      String facilityIssuer,
      String facilityId,
      String dimensionCode,
      String unit,
      Instant intervalStart,
      Instant intervalEnd,
      Instant at);

  void createReservation(
      CapacityReservationIntent request,
      long dimensionVersion,
      long policyVersion,
      java.util.List<InternalId> evidenceIds,
      InternalId reservationId,
      Instant createdAt);

  default void createReservation(
      CapacityReservationRequest request,
      long dimensionVersion,
      long policyVersion,
      java.util.List<InternalId> evidenceIds,
      InternalId reservationId,
      Instant createdAt) {
    createReservation(
        (CapacityReservationIntent) request,
        dimensionVersion,
        policyVersion,
        evidenceIds,
        reservationId,
        createdAt);
  }

  void recordOutcome(
      InternalId operationId,
      CapacityReservationResult.Outcome outcome,
      Optional<InternalId> reservationId,
      Optional<String> reasonCode,
      Instant recordedAt);

  Optional<CapacityReservationResult> findOperation(InternalId operationId);

  CapacityReservationOutcome findReservationOutcome(
      InternalId operationId, InternalId transferId, String facilityIdentifier, Instant at);

  FederatedReceivingCapacityReservationOutcome findFederatedReceivingReservationOutcome(
      InternalId operationId, InternalId receivingRequestId, String facilityIdentifier, Instant at);

  ReleaseOperationClaim lockReleaseOperation(
      InternalId releaseOperationId,
      InternalId reservationOperationId,
      CapacityReservationSubject subject,
      RequestFingerprint fingerprint,
      Instant recordedAt);

  default ReleaseOperationClaim lockReleaseOperation(
      InternalId releaseOperationId,
      InternalId reservationOperationId,
      InternalId transferId,
      RequestFingerprint fingerprint,
      Instant recordedAt) {
    return lockReleaseOperation(
        releaseOperationId,
        reservationOperationId,
        CapacityReservationSubject.transfer(transferId),
        fingerprint,
        recordedAt);
  }

  void completeReleaseOperation(
      InternalId releaseOperationId, ReleaseResult result, Instant recordedAt);

  ReleaseResult releaseReservation(
      InternalId reservationOperationId,
      CapacityReservationSubject subject,
      String facilityIdentifier,
      Instant releasedAt);

  default ReleaseResult releaseReservation(
      InternalId reservationOperationId,
      InternalId transferId,
      String facilityIdentifier,
      Instant releasedAt) {
    return releaseReservation(
        reservationOperationId,
        CapacityReservationSubject.transfer(transferId),
        facilityIdentifier,
        releasedAt);
  }

  ReleaseResult release(InternalId transferId, String facilityIdentifier, Instant releasedAt);

  List<LifecycleCandidate> findLifecycleCandidates(Instant at, int limit);

  Optional<LifecycleCandidate> lockLifecycleCandidate(InternalId reservationId);

  Optional<LifecycleCandidate> changeLifecycleStatus(
      InternalId reservationId, LifecycleStatus expected, LifecycleStatus next, Instant recordedAt);

  void recordLifecycleCheck(InternalId reservationId, Instant checkedAt);

  record LifecycleCandidate(
      InternalId reservationId,
      InternalId operationId,
      CapacityReservationSubject subject,
      ExternalReference facility,
      String dimensionCode,
      String unit,
      BigDecimal quantity,
      Instant intervalStart,
      Instant intervalEnd,
      Instant expiresAt,
      LifecycleStatus status,
      long sequence) {
    public LifecycleCandidate {
      java.util.Objects.requireNonNull(reservationId, "reservation id");
      java.util.Objects.requireNonNull(operationId, "operation id");
      java.util.Objects.requireNonNull(subject, "reservation subject");
      java.util.Objects.requireNonNull(facility, "facility");
      java.util.Objects.requireNonNull(dimensionCode, "dimension code");
      java.util.Objects.requireNonNull(unit, "unit");
      java.util.Objects.requireNonNull(quantity, "quantity");
      java.util.Objects.requireNonNull(intervalStart, "interval start");
      java.util.Objects.requireNonNull(intervalEnd, "interval end");
      java.util.Objects.requireNonNull(expiresAt, "expiry");
      java.util.Objects.requireNonNull(status, "status");
      if (sequence < 1) {
        throw new IllegalArgumentException("reservation sequence must be positive");
      }
    }

    public InternalId transferId() {
      if (subject.kind() != CapacityReservationSubject.Kind.TRANSFER) {
        throw new IllegalStateException("federated receiving reservation has no Transfer id");
      }
      return subject.id();
    }
  }

  enum LifecycleStatus {
    ACTIVE,
    AT_RISK,
    RELEASED,
    EXPIRED
  }

  record ReleaseResult(
      ReleaseOutcome outcome,
      Optional<InternalId> reservationId,
      long sequence,
      boolean changed,
      Optional<Instant> expiresAt) {
    public ReleaseResult {
      java.util.Objects.requireNonNull(outcome, "release outcome");
      reservationId = java.util.Objects.requireNonNull(reservationId, "reservation id");
      expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiry");
      if ((outcome == ReleaseOutcome.RELEASED || outcome == ReleaseOutcome.EXPIRED)
          != reservationId.isPresent()) {
        throw new IllegalArgumentException("reservation lifecycle result is inconsistent");
      }
      if (sequence < 0 || changed && sequence < 2) {
        throw new IllegalArgumentException("reservation sequence cannot be negative");
      }
    }
  }

  record ReleaseOperationClaim(boolean fingerprintMatches, Optional<ReleaseResult> current) {
    public ReleaseOperationClaim {
      current = java.util.Objects.requireNonNull(current, "current release operation outcome");
    }
  }

  enum ReleaseOutcome {
    RELEASED,
    EXPIRED,
    NOT_RESERVED,
    SCOPE_MISMATCH
  }

  final class AmbiguousReleaseException extends RuntimeException {
    public AmbiguousReleaseException() {
      super("Transfer has more than one active Capacity reservation; an operation ID is required");
    }
  }

  enum ReceiptState {
    OWNER,
    REPLAY,
    KEY_REUSED,
    IN_PROGRESS
  }

  record ReceiptClaim(ReceiptState state, Optional<CapacityReservationResult> result) {
    public ReceiptClaim {
      java.util.Objects.requireNonNull(state, "receipt state");
      result = java.util.Objects.requireNonNull(result, "receipt result");
    }
  }

  record OperationClaim(boolean fingerprintMatches, Optional<CapacityReservationResult> current) {
    public OperationClaim {
      current = java.util.Objects.requireNonNull(current, "current operation outcome");
    }
  }
}
