package org.ayuvahak.capacity.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Complete source, freshness and optional readiness policy for one dimension version. */
public record CapacityPolicyDefinition(
    String dimensionCode,
    long dimensionVersion,
    List<CapacitySourcePriority> sources,
    long maximumObservationAgeSeconds,
    Long defaultValiditySeconds,
    long futureSkewToleranceSeconds,
    BigDecimal minimumPassingQuantity,
    Set<String> passingStates,
    String reasonCode) {

  private static final Pattern DIMENSION_CODE = Pattern.compile("[A-Z][A-Z0-9._-]{0,63}");

  public CapacityPolicyDefinition {
    Objects.requireNonNull(dimensionCode, "dimension code");
    if (!DIMENSION_CODE.matcher(dimensionCode).matches()) {
      throw new IllegalArgumentException("dimension code has an invalid format");
    }
    if (dimensionVersion < 1) {
      throw new IllegalArgumentException("dimension version must be positive");
    }
    sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    passingStates = Set.copyOf(Objects.requireNonNull(passingStates, "passing states"));
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("at least one source policy is required");
    }
    if (sources.stream().map(CapacitySourcePriority::sourceCode).distinct().count()
        != sources.size()) {
      throw new IllegalArgumentException("source codes must be unique within a policy version");
    }
    if (maximumObservationAgeSeconds <= 0) {
      throw new IllegalArgumentException("maximum observation age must be positive");
    }
    if (defaultValiditySeconds != null && defaultValiditySeconds <= 0) {
      throw new IllegalArgumentException("default validity must be positive when configured");
    }
    if (futureSkewToleranceSeconds < 0) {
      throw new IllegalArgumentException("future skew tolerance cannot be negative");
    }
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,79}")) {
      throw new IllegalArgumentException("reason code has an invalid format");
    }
  }

  public void validateFor(CapacityDimension dimension) {
    if (!dimension.code().equals(dimensionCode) || dimension.version() != dimensionVersion) {
      throw new IllegalArgumentException("policy must target the exact dimension version");
    }
    if (!dimension.usedForReadiness()) {
      if (minimumPassingQuantity != null || !passingStates.isEmpty()) {
        throw new IllegalArgumentException("readiness rules are not allowed for this dimension");
      }
      return;
    }
    switch (dimension.valueKind()) {
      case QUANTITY -> {
        if (minimumPassingQuantity == null || !passingStates.isEmpty()) {
          throw new IllegalArgumentException("quantity readiness requires one minimum quantity");
        }
        if (minimumPassingQuantity.signum() < 0
            || (dimension.minimumValue() != null
                && minimumPassingQuantity.compareTo(dimension.minimumValue()) < 0)
            || (dimension.maximumValue() != null
                && minimumPassingQuantity.compareTo(dimension.maximumValue()) > 0)) {
          throw new IllegalArgumentException("readiness quantity is outside the dimension range");
        }
      }
      case STATE -> {
        if (minimumPassingQuantity != null
            || passingStates.isEmpty()
            || !dimension.allowedStates().containsAll(passingStates)) {
          throw new IllegalArgumentException("state readiness must use allowed controlled states");
        }
      }
    }
  }
}
