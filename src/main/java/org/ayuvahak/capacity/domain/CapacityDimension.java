package org.ayuvahak.capacity.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;

/** Immutable, attributable version of a governed Capacity dimension. */
public record CapacityDimension(
    InternalId revisionId,
    String code,
    long version,
    String displayName,
    String meaning,
    CapacityValueKind valueKind,
    String unit,
    BigDecimal minimumValue,
    BigDecimal maximumValue,
    Set<String> allowedStates,
    boolean usedForReadiness,
    boolean reservable,
    String reasonCode,
    ActorReference changedBy,
    Instant changedAt) {

  public CapacityDimension {
    Objects.requireNonNull(revisionId, "revision id");
    Objects.requireNonNull(changedBy, "changed by");
    Objects.requireNonNull(changedAt, "changed at");
    allowedStates = Set.copyOf(Objects.requireNonNull(allowedStates, "allowed states"));
    if (version < 1) {
      throw new IllegalArgumentException("dimension version must be positive");
    }
    new CapacityDimensionDefinition(
        code,
        displayName,
        meaning,
        valueKind,
        unit,
        minimumValue,
        maximumValue,
        allowedStates,
        usedForReadiness,
        reservable,
        reasonCode);
  }

  public static CapacityDimension version(
      InternalId revisionId,
      long version,
      CapacityDimensionDefinition definition,
      ActorReference changedBy,
      Instant changedAt) {
    return new CapacityDimension(
        revisionId,
        definition.code(),
        version,
        definition.displayName(),
        definition.meaning(),
        definition.valueKind(),
        definition.unit(),
        definition.minimumValue(),
        definition.maximumValue(),
        definition.allowedStates(),
        definition.usedForReadiness(),
        definition.reservable(),
        definition.reasonCode(),
        changedBy,
        changedAt);
  }
}
