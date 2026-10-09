package org.ayuvahak.capacity.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;

/** Immutable version of source-precedence, freshness and readiness policy. */
public record CapacityPolicy(
    InternalId revisionId,
    String dimensionCode,
    long version,
    long dimensionVersion,
    List<CapacitySourcePriority> sources,
    long maximumObservationAgeSeconds,
    Long defaultValiditySeconds,
    long futureSkewToleranceSeconds,
    BigDecimal minimumPassingQuantity,
    Set<String> passingStates,
    String reasonCode,
    ActorReference changedBy,
    Instant changedAt) {

  public CapacityPolicy {
    Objects.requireNonNull(revisionId, "revision id");
    Objects.requireNonNull(changedBy, "changed by");
    Objects.requireNonNull(changedAt, "changed at");
    if (version < 1) {
      throw new IllegalArgumentException("policy version must be positive");
    }
    new CapacityPolicyDefinition(
        dimensionCode,
        dimensionVersion,
        sources,
        maximumObservationAgeSeconds,
        defaultValiditySeconds,
        futureSkewToleranceSeconds,
        minimumPassingQuantity,
        passingStates,
        reasonCode);
    sources = List.copyOf(sources);
    passingStates = Set.copyOf(passingStates);
  }

  public static CapacityPolicy version(
      InternalId revisionId,
      long version,
      CapacityPolicyDefinition definition,
      ActorReference changedBy,
      Instant changedAt) {
    return new CapacityPolicy(
        revisionId,
        definition.dimensionCode(),
        version,
        definition.dimensionVersion(),
        definition.sources(),
        definition.maximumObservationAgeSeconds(),
        definition.defaultValiditySeconds(),
        definition.futureSkewToleranceSeconds(),
        definition.minimumPassingQuantity(),
        definition.passingStates(),
        definition.reasonCode(),
        changedBy,
        changedAt);
  }
}
