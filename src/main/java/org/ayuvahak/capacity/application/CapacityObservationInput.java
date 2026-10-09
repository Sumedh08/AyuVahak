package org.ayuvahak.capacity.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityObservationKind;
import org.ayuvahak.capacity.domain.CapacityReportingMethod;
import org.ayuvahak.capacity.domain.CapacityValueKind;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Validated, adapter-neutral fields for one report or append-only correction. */
public record CapacityObservationInput(
    String dimensionCode,
    String sourceCode,
    ExternalReference sourceObservation,
    CapacityObservationKind kind,
    Optional<CapacityValueKind> valueKind,
    Optional<BigDecimal> quantity,
    Optional<String> state,
    Optional<String> unit,
    Instant observedAt,
    Optional<Instant> validUntil,
    CapacityReportingMethod reportingMethod,
    String reasonCode,
    Optional<InternalId> supersedesObservationId) {

  public CapacityObservationInput {
    Objects.requireNonNull(sourceObservation, "source observation reference");
    Objects.requireNonNull(kind, "observation kind");
    valueKind = Objects.requireNonNull(valueKind, "value kind");
    quantity = Objects.requireNonNull(quantity, "quantity");
    state = Objects.requireNonNull(state, "state");
    unit = Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(observedAt, "observed at");
    validUntil = Objects.requireNonNull(validUntil, "valid until");
    Objects.requireNonNull(reportingMethod, "reporting method");
    supersedesObservationId =
        Objects.requireNonNull(supersedesObservationId, "superseded observation id");
  }
}
