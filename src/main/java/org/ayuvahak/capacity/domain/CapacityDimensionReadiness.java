package org.ayuvahak.capacity.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Readiness result and retained source evidence for one explicitly requested dimension. */
public record CapacityDimensionReadiness(
    String dimensionCode,
    CapacityReadinessOutcome outcome,
    CapacityEvidenceFreshness freshness,
    OptionalLong dimensionVersion,
    OptionalLong policyVersion,
    String projectionVersion,
    Optional<CapacityValueKind> valueKind,
    Optional<BigDecimal> quantity,
    Optional<String> state,
    Optional<String> unit,
    List<CapacityObservation> evidence,
    boolean fallbackUsed,
    List<String> reasonCodes) {

  public CapacityDimensionReadiness {
    if (dimensionCode == null || !dimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("dimension code must be a stable identifier");
    }
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(freshness, "freshness");
    Objects.requireNonNull(dimensionVersion, "dimension version");
    Objects.requireNonNull(policyVersion, "policy version");
    if (projectionVersion == null || !projectionVersion.matches("[a-z][a-z0-9-]{0,63}")) {
      throw new IllegalArgumentException("projection version must be a stable identifier");
    }
    valueKind = Objects.requireNonNull(valueKind, "value kind");
    quantity = Objects.requireNonNull(quantity, "quantity");
    state = Objects.requireNonNull(state, "state");
    unit = Objects.requireNonNull(unit, "unit");
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reason codes"));
    if (reasonCodes.isEmpty()
        || reasonCodes.stream().anyMatch(reason -> !reason.matches("[A-Z][A-Z0-9_]{0,79}"))) {
      throw new IllegalArgumentException("at least one stable reason code is required");
    }
  }
}
