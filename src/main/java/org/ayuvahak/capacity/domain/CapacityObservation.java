package org.ayuvahak.capacity.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Immutable, typed Capacity fact, retaining both source identity and reporting attribution. */
public record CapacityObservation(
    InternalId id,
    ExternalReference facility,
    String dimensionCode,
    long dimensionVersion,
    CapacityObservationKind kind,
    Optional<CapacityValueKind> valueKind,
    Optional<BigDecimal> quantity,
    Optional<String> state,
    Optional<String> unit,
    String sourceCode,
    ExternalReference sourceObservation,
    CapacityReportingMethod reportingMethod,
    ActorReference reportedBy,
    Instant observedAt,
    Instant receivedAt,
    Optional<Instant> sourceValidUntil,
    Optional<Instant> validUntil,
    long policyVersion,
    boolean futureSkewed,
    String reasonCode,
    Optional<InternalId> supersedesObservationId) {

  public CapacityObservation {
    Objects.requireNonNull(id, "observation id");
    Objects.requireNonNull(facility, "facility reference");
    if (!"Facility".equals(facility.resourceType())) {
      throw new IllegalArgumentException("Capacity observation requires a Facility reference");
    }
    if (dimensionCode == null || !dimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("dimension code must be a stable identifier");
    }
    if (dimensionVersion < 1 || policyVersion < 1) {
      throw new IllegalArgumentException("dimension and policy versions must be positive");
    }
    Objects.requireNonNull(kind, "observation kind");
    valueKind = Objects.requireNonNull(valueKind, "value kind");
    quantity = Objects.requireNonNull(quantity, "quantity");
    state = Objects.requireNonNull(state, "state");
    unit = Objects.requireNonNull(unit, "unit");
    sourceCode = requireSourceCode(sourceCode);
    Objects.requireNonNull(sourceObservation, "source observation reference");
    if (!"CapacityObservation".equals(sourceObservation.resourceType())) {
      throw new IllegalArgumentException("source reference must identify an observation");
    }
    Objects.requireNonNull(reportingMethod, "reporting method");
    Objects.requireNonNull(reportedBy, "reporting actor");
    Objects.requireNonNull(observedAt, "observed at");
    Objects.requireNonNull(receivedAt, "received at");
    sourceValidUntil = Objects.requireNonNull(sourceValidUntil, "source validity");
    validUntil = Objects.requireNonNull(validUntil, "effective validity");
    validUntil.ifPresent(
        expiry -> {
          if (!expiry.isAfter(observedAt)) {
            throw new IllegalArgumentException("validUntil must be later than observedAt");
          }
        });
    reasonCode = requireToken(reasonCode, "reason code");
    supersedesObservationId =
        Objects.requireNonNull(supersedesObservationId, "superseded observation reference");

    if (kind == CapacityObservationKind.RETRACTION) {
      if (valueKind.isPresent()
          || quantity.isPresent()
          || state.isPresent()
          || unit.isPresent()
          || validUntil.isPresent()
          || sourceValidUntil.isPresent()
          || supersedesObservationId.isEmpty()) {
        throw new IllegalArgumentException("a retraction has no value and must supersede a fact");
      }
    } else {
      if (valueKind.isEmpty()) {
        throw new IllegalArgumentException("value observation metadata is incomplete");
      }
      validateTypedValue(valueKind.orElseThrow(), quantity, state, unit);
    }
  }

  public boolean hasSameSourceFact(CapacityObservation other) {
    return sourceCode.equals(other.sourceCode)
        && sourceObservation.equals(other.sourceObservation)
        && facility.equals(other.facility)
        && dimensionCode.equals(other.dimensionCode)
        && kind == other.kind
        && valueKind.equals(other.valueKind)
        && quantity
            .map(BigDecimal::stripTrailingZeros)
            .equals(other.quantity.map(BigDecimal::stripTrailingZeros))
        && state.equals(other.state)
        && unit.equals(other.unit)
        && reportingMethod == other.reportingMethod
        && observedAt.equals(other.observedAt)
        && sourceValidUntil.equals(other.sourceValidUntil)
        && reasonCode.equals(other.reasonCode)
        && supersedesObservationId.equals(other.supersedesObservationId);
  }

  public boolean hasSameSourceFact(
      ExternalReference submittedFacility,
      String submittedDimensionCode,
      CapacityObservationKind submittedKind,
      Optional<CapacityValueKind> submittedValueKind,
      Optional<BigDecimal> submittedQuantity,
      Optional<String> submittedState,
      Optional<String> submittedUnit,
      String submittedSourceCode,
      ExternalReference submittedSourceObservation,
      CapacityReportingMethod submittedReportingMethod,
      Instant submittedObservedAt,
      Optional<Instant> submittedSourceValidUntil,
      String submittedReasonCode,
      Optional<InternalId> submittedSupersedesObservationId) {
    return sourceCode.equals(submittedSourceCode)
        && sourceObservation.equals(submittedSourceObservation)
        && facility.equals(submittedFacility)
        && dimensionCode.equals(submittedDimensionCode)
        && kind == submittedKind
        && valueKind.equals(submittedValueKind)
        && quantity
            .map(BigDecimal::stripTrailingZeros)
            .equals(submittedQuantity.map(value -> value.stripTrailingZeros()))
        && state.equals(submittedState)
        && unit.equals(submittedUnit)
        && reportingMethod == submittedReportingMethod
        && observedAt.equals(submittedObservedAt)
        && sourceValidUntil.equals(submittedSourceValidUntil)
        && reasonCode.equals(submittedReasonCode)
        && supersedesObservationId.equals(submittedSupersedesObservationId);
  }

  private static void validateTypedValue(
      CapacityValueKind valueKind,
      Optional<BigDecimal> quantity,
      Optional<String> state,
      Optional<String> unit) {
    switch (valueKind) {
      case QUANTITY -> {
        if (quantity.isEmpty()
            || quantity.orElseThrow().signum() < 0
            || state.isPresent()
            || unit.isEmpty()) {
          throw new IllegalArgumentException("quantity observation has an invalid value shape");
        }
      }
      case STATE -> {
        if (quantity.isPresent()
            || state.isEmpty()
            || unit.isPresent()
            || !state.orElseThrow().matches("[A-Z][A-Z0-9._-]{0,63}")) {
          throw new IllegalArgumentException("state observation has an invalid value shape");
        }
      }
    }
  }

  private static String requireToken(String value, String label) {
    if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,79}")) {
      throw new IllegalArgumentException(label + " must be a stable uppercase token");
    }
    return value;
  }

  private static String requireSourceCode(String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("source code has an invalid format");
    }
    return value;
  }

  @Override
  public String toString() {
    return "CapacityObservation[id=" + id + ", kind=" + kind + ", values=redacted]";
  }
}
