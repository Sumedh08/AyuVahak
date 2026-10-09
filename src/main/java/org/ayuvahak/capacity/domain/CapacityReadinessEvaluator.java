package org.ayuvahak.capacity.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Applies versioned Capacity readiness rules to the query-time current evidence projection. */
public final class CapacityReadinessEvaluator {

  public CapacityDimensionReadiness evaluate(
      String dimensionCode,
      Optional<CapacityDimension> dimension,
      Optional<CapacityPolicy> policy,
      CapacityEvidenceProjection projection) {
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(projection, "projection");
    if (!dimensionCode.equals(projection.dimensionCode())) {
      throw new IllegalArgumentException("projection must match the requested dimension");
    }

    if (dimension.isEmpty()) {
      return result(
          dimensionCode,
          CapacityReadinessOutcome.UNKNOWN,
          projection,
          List.of("DIMENSION_UNKNOWN"));
    }
    CapacityDimension configuredDimension = dimension.orElseThrow();
    if (!configuredDimension.code().equals(dimensionCode)) {
      return result(
          dimensionCode,
          CapacityReadinessOutcome.UNKNOWN,
          projection,
          List.of("DIMENSION_CONFIGURATION_INVALID"));
    }
    if (!configuredDimension.usedForReadiness()) {
      return result(
          dimensionCode,
          CapacityReadinessOutcome.UNKNOWN,
          projection,
          List.of("DIMENSION_NOT_CONFIGURED_FOR_READINESS"));
    }
    if (policy.isEmpty()) {
      return result(
          dimensionCode,
          CapacityReadinessOutcome.UNKNOWN,
          projection,
          List.of("READINESS_POLICY_UNCONFIGURED"));
    }

    CapacityPolicy configuredPolicy = policy.orElseThrow();
    try {
      new CapacityPolicyDefinition(
              configuredPolicy.dimensionCode(),
              configuredPolicy.dimensionVersion(),
              configuredPolicy.sources(),
              configuredPolicy.maximumObservationAgeSeconds(),
              configuredPolicy.defaultValiditySeconds(),
              configuredPolicy.futureSkewToleranceSeconds(),
              configuredPolicy.minimumPassingQuantity(),
              configuredPolicy.passingStates(),
              configuredPolicy.reasonCode())
          .validateFor(configuredDimension);
    } catch (IllegalArgumentException invalidPolicy) {
      return result(
          dimensionCode,
          CapacityReadinessOutcome.UNKNOWN,
          projection,
          List.of("READINESS_POLICY_INVALID"));
    }
    if (configuredPolicy.dimensionVersion() != configuredDimension.version()
        || projection.dimensionVersion().isEmpty()
        || projection.dimensionVersion().getAsLong() != configuredDimension.version()
        || projection.policyVersion().isEmpty()
        || projection.policyVersion().getAsLong() != configuredPolicy.version()) {
      return result(
          dimensionCode,
          CapacityReadinessOutcome.UNKNOWN,
          projection,
          List.of("READINESS_POLICY_VERSION_MISMATCH"));
    }

    if (projection.freshness() != CapacityEvidenceFreshness.CURRENT) {
      return freshnessOutcome(dimensionCode, projection);
    }
    if (projection.evidence().isEmpty() || projection.valueKind().isEmpty()) {
      return result(
          dimensionCode,
          CapacityReadinessOutcome.UNKNOWN,
          projection,
          List.of("CURRENT_EVIDENCE_VALUE_MISSING"));
    }

    List<String> reasons = new ArrayList<>();
    CapacityReadinessOutcome outcome;
    if (configuredDimension.valueKind() == CapacityValueKind.QUANTITY
        && projection.valueKind().orElseThrow() == CapacityValueKind.QUANTITY
        && projection.quantity().isPresent()
        && projection.unit().filter(configuredDimension.unit()::equals).isPresent()) {
      if (projection.quantity().orElseThrow().compareTo(configuredPolicy.minimumPassingQuantity())
          >= 0) {
        outcome = CapacityReadinessOutcome.READY;
        reasons.add("READINESS_RULE_PASSED");
      } else {
        outcome = CapacityReadinessOutcome.NOT_READY;
        reasons.add("BELOW_MINIMUM_QUANTITY");
      }
    } else if (configuredDimension.valueKind() == CapacityValueKind.STATE
        && projection.valueKind().orElseThrow() == CapacityValueKind.STATE
        && projection.state().isPresent()
        && projection.unit().isEmpty()
        && projection.quantity().isEmpty()) {
      if (configuredPolicy.passingStates().contains(projection.state().orElseThrow())) {
        outcome = CapacityReadinessOutcome.READY;
        reasons.add("READINESS_RULE_PASSED");
      } else {
        outcome = CapacityReadinessOutcome.NOT_READY;
        reasons.add("STATE_NOT_PASSING");
      }
    } else {
      outcome = CapacityReadinessOutcome.UNKNOWN;
      reasons.add("CURRENT_EVIDENCE_VALUE_INVALID");
    }
    reasons.addAll(projection.reasonCodes());
    return result(dimensionCode, outcome, projection, reasons);
  }

  private static CapacityDimensionReadiness freshnessOutcome(
      String dimensionCode, CapacityEvidenceProjection projection) {
    CapacityReadinessOutcome outcome =
        switch (projection.freshness()) {
          case CURRENT -> throw new IllegalStateException("current evidence was already handled");
          case STALE, EXPIRED -> CapacityReadinessOutcome.STALE;
          case CONFLICTED -> CapacityReadinessOutcome.CONFLICTED;
          case UNKNOWN -> CapacityReadinessOutcome.UNKNOWN;
        };
    List<String> reasons = new ArrayList<>();
    reasons.add(
        switch (projection.freshness()) {
          case CURRENT -> throw new IllegalStateException("current evidence was already handled");
          case STALE -> "EVIDENCE_STALE";
          case EXPIRED -> "EVIDENCE_EXPIRED";
          case CONFLICTED -> "EVIDENCE_CONFLICTED";
          case UNKNOWN -> "EVIDENCE_UNKNOWN";
        });
    reasons.addAll(projection.reasonCodes());
    return result(dimensionCode, outcome, projection, reasons);
  }

  private static CapacityDimensionReadiness result(
      String dimensionCode,
      CapacityReadinessOutcome outcome,
      CapacityEvidenceProjection projection,
      List<String> reasons) {
    return new CapacityDimensionReadiness(
        dimensionCode,
        outcome,
        projection.freshness(),
        projection.dimensionVersion(),
        projection.policyVersion(),
        projection.projectionVersion(),
        projection.valueKind(),
        projection.quantity(),
        projection.state(),
        projection.unit(),
        projection.evidence(),
        projection.fallbackUsed(),
        reasons);
  }
}
