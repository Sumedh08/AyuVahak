package org.ayuvahak.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Safe reservation status returned to an outbound owner; it contains no aggregate values. */
public record DisclosureReservation(
    UUID reservationId,
    String policyKey,
    int policyVersion,
    UUID operationId,
    String operationFingerprint,
    Set<UUID> lineageIds,
    long historyCheckpoint,
    DisclosureReservationState state,
    String reasonCode,
    Instant createdAt,
    Instant updatedAt,
    boolean replayed) {

  public DisclosureReservation {
    Objects.requireNonNull(reservationId, "reservation id");
    Objects.requireNonNull(policyKey, "policy key");
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(operationFingerprint, "operation fingerprint");
    lineageIds = Set.copyOf(Objects.requireNonNull(lineageIds, "lineage ids"));
    Objects.requireNonNull(state, "reservation state");
    Objects.requireNonNull(reasonCode, "reason code");
    Objects.requireNonNull(createdAt, "created at");
    Objects.requireNonNull(updatedAt, "updated at");
    if (policyVersion < 1
        || historyCheckpoint < 0
        || !operationFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("disclosure reservation metadata is invalid");
    }
  }

  @Override
  public String toString() {
    return "DisclosureReservation[reservationId="
        + reservationId
        + ", policyKey="
        + policyKey
        + ", policyVersion="
        + policyVersion
        + ", state="
        + state
        + ", reasonCode="
        + reasonCode
        + ", lineageCount="
        + lineageIds.size()
        + ", references=redacted]";
  }
}
