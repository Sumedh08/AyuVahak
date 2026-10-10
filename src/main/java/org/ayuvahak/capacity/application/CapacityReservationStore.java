package org.ayuvahak.capacity.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
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
      InternalId transferId,
      RequestFingerprint fingerprint,
      Instant recordedAt);

  BigDecimal sumActiveReservations(
      String facilityIssuer,
      String facilityId,
      String dimensionCode,
      String unit,
      Instant intervalStart,
      Instant intervalEnd,
      Instant at);

  void createReservation(
      CapacityReservationRequest request,
      long dimensionVersion,
      long policyVersion,
      java.util.List<InternalId> evidenceIds,
      InternalId reservationId,
      Instant createdAt);

  void recordOutcome(
      InternalId operationId,
      CapacityReservationResult.Outcome outcome,
      Optional<InternalId> reservationId,
      Optional<String> reasonCode,
      Instant recordedAt);

  Optional<CapacityReservationResult> findOperation(InternalId operationId);

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
