package org.ayuvahak.capacity.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Deployment-governed meaning and permitted value shape for one Capacity dimension. */
public record CapacityDimensionDefinition(
    String code,
    String displayName,
    String meaning,
    CapacityValueKind valueKind,
    String unit,
    BigDecimal minimumValue,
    BigDecimal maximumValue,
    Set<String> allowedStates,
    boolean usedForReadiness,
    boolean reservable,
    String reasonCode) {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9._-]{0,63}");
  private static final Pattern UNIT = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,31}");
  private static final Pattern STATE = Pattern.compile("[A-Z][A-Z0-9._-]{0,63}");
  private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

  public CapacityDimensionDefinition {
    requireMatch(code, CODE, "dimension code");
    requireText(displayName, 200, "display name");
    requireText(meaning, 1000, "meaning");
    Objects.requireNonNull(valueKind, "value kind");
    allowedStates = Set.copyOf(Objects.requireNonNull(allowedStates, "allowed states"));
    requireMatch(reasonCode, REASON, "reason code");
    switch (valueKind) {
      case QUANTITY -> {
        requireMatch(unit, UNIT, "unit");
        if (!allowedStates.isEmpty()) {
          throw new IllegalArgumentException(
              "quantity dimensions cannot define categorical states");
        }
        if (minimumValue != null
            && maximumValue != null
            && minimumValue.compareTo(maximumValue) > 0) {
          throw new IllegalArgumentException("minimum value cannot exceed maximum value");
        }
        if ((minimumValue != null && minimumValue.signum() < 0)
            || (maximumValue != null && maximumValue.signum() < 0)) {
          throw new IllegalArgumentException("capacity quantity ranges cannot be negative");
        }
      }
      case STATE -> {
        if (unit != null || minimumValue != null || maximumValue != null) {
          throw new IllegalArgumentException("state dimensions cannot define quantities or units");
        }
        if (allowedStates.isEmpty()
            || allowedStates.stream().anyMatch(state -> !STATE.matcher(state).matches())) {
          throw new IllegalArgumentException("state dimensions require controlled state codes");
        }
      }
    }
    if (reservable && (valueKind != CapacityValueKind.QUANTITY || unit == null)) {
      throw new IllegalArgumentException("only quantity dimensions with a unit may be reservable");
    }
  }

  private static void requireMatch(String value, Pattern pattern, String label) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " has an invalid format");
    }
  }

  private static void requireText(String value, int maxLength, String label) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > maxLength) {
      throw new IllegalArgumentException(label + " must be bounded, nonblank text");
    }
  }
}
