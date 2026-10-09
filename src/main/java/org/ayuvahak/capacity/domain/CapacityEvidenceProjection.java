package org.ayuvahak.capacity.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Deterministic, rebuildable query result derived from immutable evidence and one policy version.
 */
public record CapacityEvidenceProjection(
    String dimensionCode,
    OptionalLong dimensionVersion,
    OptionalLong policyVersion,
    CapacityEvidenceFreshness freshness,
    Optional<CapacityValueKind> valueKind,
    Optional<BigDecimal> quantity,
    Optional<String> state,
    Optional<String> unit,
    List<CapacityObservation> evidence,
    boolean fallbackUsed,
    List<String> reasonCodes,
    String projectionVersion) {

  public CapacityEvidenceProjection {
    Objects.requireNonNull(dimensionCode, "dimension code");
    Objects.requireNonNull(dimensionVersion, "dimension version");
    Objects.requireNonNull(policyVersion, "policy version");
    Objects.requireNonNull(freshness, "freshness");
    valueKind = Objects.requireNonNull(valueKind, "value kind");
    quantity = Objects.requireNonNull(quantity, "quantity");
    state = Objects.requireNonNull(state, "state");
    unit = Objects.requireNonNull(unit, "unit");
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reason codes"));
    if (projectionVersion == null || !projectionVersion.matches("[a-z][a-z0-9-]{0,63}")) {
      throw new IllegalArgumentException("projection version must be a stable identifier");
    }
  }
}
