package org.ayuvahak.matching.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Matching-owned, minimized view of remote Capacity evidence. */
public record RemoteCapacityReadiness(
    RemoteFacilityReference facility,
    String dimensionCode,
    RemoteCapacityOutcome outcome,
    RemoteEvidenceFreshness freshness,
    long sourceSequence,
    long dimensionVersion,
    long policyVersion,
    String sourceProfileVersion,
    Optional<String> evidenceId,
    Optional<BigDecimal> quantity,
    Optional<String> state,
    Optional<String> unit,
    Instant observedAt,
    Instant receivedAt,
    Instant validUntil,
    List<String> reasonCodes) {

  public RemoteCapacityReadiness {
    Objects.requireNonNull(facility, "remote Facility reference");
    Objects.requireNonNull(outcome, "readiness outcome");
    Objects.requireNonNull(freshness, "evidence freshness");
    evidenceId = Objects.requireNonNull(evidenceId, "evidence ID");
    quantity = Objects.requireNonNull(quantity, "quantity");
    state = Objects.requireNonNull(state, "state");
    unit = Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(observedAt, "source observed time");
    Objects.requireNonNull(receivedAt, "local received time");
    Objects.requireNonNull(validUntil, "source validity deadline");
    reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reason codes"));
    if (outcome == RemoteCapacityOutcome.READY
        && (freshness != RemoteEvidenceFreshness.CURRENT || evidenceId.isEmpty())) {
      throw new IllegalArgumentException("READY requires current identified source evidence");
    }
    if (outcome != RemoteCapacityOutcome.READY
        && (quantity.isPresent() || state.isPresent() || unit.isPresent())) {
      throw new IllegalArgumentException("non-ready evidence cannot expose capacity values");
    }
    if (reasonCodes.isEmpty()
        || reasonCodes.stream().anyMatch(reason -> !reason.matches("[A-Z][A-Z0-9_]{0,79}"))) {
      throw new IllegalArgumentException("at least one stable reason code is required");
    }
  }
}
