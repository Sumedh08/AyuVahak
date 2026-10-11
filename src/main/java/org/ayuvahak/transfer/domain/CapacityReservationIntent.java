package org.ayuvahak.transfer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Transfer-owned retry/lease state for a Capacity command; it does not own the resulting hold. */
public record CapacityReservationIntent(
    InternalId operationId,
    InternalId transferId,
    ExternalReference organization,
    ExternalReference facility,
    Optional<String> dimensionCode,
    Optional<String> unit,
    Optional<BigDecimal> quantity,
    Optional<Instant> intervalStart,
    Optional<Instant> intervalEnd,
    Optional<Instant> expiresAt,
    Outcome outcome,
    Optional<InternalId> reservationId,
    Optional<String> reasonCode,
    int attemptCount,
    Instant nextAttemptAt) {

  public CapacityReservationIntent {
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(organization, "destination Organization");
    Objects.requireNonNull(facility, "destination Facility");
    if (!"urn:ayuvahak:directory".equals(organization.issuer().value())
        || !"Organization".equals(organization.resourceType())
        || !"urn:ayuvahak:directory".equals(facility.issuer().value())
        || !"Facility".equals(facility.resourceType())) {
      throw new IllegalArgumentException("reservation context must use Directory references");
    }
    dimensionCode = Objects.requireNonNull(dimensionCode, "dimension code");
    unit = Objects.requireNonNull(unit, "unit");
    quantity = Objects.requireNonNull(quantity, "quantity");
    intervalStart = Objects.requireNonNull(intervalStart, "interval start");
    intervalEnd = Objects.requireNonNull(intervalEnd, "interval end");
    expiresAt = Objects.requireNonNull(expiresAt, "expiry");
    Objects.requireNonNull(outcome, "outcome");
    reservationId = Objects.requireNonNull(reservationId, "reservation id");
    reasonCode = Objects.requireNonNull(reasonCode, "reason code");
    Objects.requireNonNull(nextAttemptAt, "next attempt time");
    if (!operationId.equals(transferId) || attemptCount < 0) {
      throw new IllegalArgumentException("reservation operation must use its stable Transfer id");
    }
    boolean configured =
        dimensionCode.isPresent()
            && unit.isPresent()
            && quantity.filter(value -> value.signum() > 0).isPresent()
            && intervalStart.isPresent()
            && intervalEnd.isPresent()
            && expiresAt.isPresent();
    boolean unconfigured =
        dimensionCode.isEmpty()
            && unit.isEmpty()
            && quantity.isEmpty()
            && intervalStart.isEmpty()
            && intervalEnd.isEmpty()
            && expiresAt.isEmpty();
    if (!configured && !unconfigured) {
      throw new IllegalArgumentException("reservation inputs must be fully configured or absent");
    }
    if (configured
        && (!intervalEnd.orElseThrow().isAfter(intervalStart.orElseThrow())
            || !expiresAt.orElseThrow().equals(intervalEnd.orElseThrow()))) {
      throw new IllegalArgumentException("reservation interval and expiry are inconsistent");
    }
    boolean hasReservation =
        outcome == Outcome.RESERVED
            || outcome == Outcome.AT_RISK
            || outcome == Outcome.RELEASED
            || outcome == Outcome.EXPIRED;
    if (hasReservation != reservationId.isPresent()) {
      throw new IllegalArgumentException(
          "reservation lifecycle outcome and reference are inconsistent");
    }
    if (configured == (outcome == Outcome.UNCONFIGURED)) {
      throw new IllegalArgumentException("only an unconfigured outcome omits reservation inputs");
    }
  }

  public enum Outcome {
    PENDING,
    /** A committed Transfer claim; Capacity is called only after this claim transaction closes. */
    IN_PROGRESS,
    RESERVED,
    CONFLICT,
    UNKNOWN,
    UNAVAILABLE,
    INVALID,
    UNCONFIGURED,
    AT_RISK,
    RELEASED,
    EXPIRED,
    NOT_RESERVED
  }
}
