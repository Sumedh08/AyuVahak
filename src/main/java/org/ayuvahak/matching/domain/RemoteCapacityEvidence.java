package org.ayuvahak.matching.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Bounded, source-attributed evidence returned by the remote Capacity owner. */
public record RemoteCapacityEvidence(
    RemoteFacilityReference facility,
    String dimensionCode,
    long sourceSequence,
    long dimensionVersion,
    long policyVersion,
    String sourceProfileVersion,
    String evidenceId,
    RemoteCapacityOutcome outcome,
    RemoteEvidenceFreshness freshness,
    boolean complete,
    Optional<BigDecimal> quantity,
    Optional<String> state,
    Optional<String> unit,
    Instant observedAt,
    Instant receivedAt,
    Instant validUntil,
    boolean sourceConflict) {

  public RemoteCapacityEvidence {
    Objects.requireNonNull(facility, "remote Facility reference");
    if (dimensionCode == null || !dimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("dimension code must be a stable identifier");
    }
    if (sourceSequence < 1 || dimensionVersion < 1 || policyVersion < 1) {
      throw new IllegalArgumentException("source sequence and source versions must be positive");
    }
    if (sourceProfileVersion == null
        || !sourceProfileVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("source profile version must be a stable identifier");
    }
    if (evidenceId == null || !evidenceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("evidence ID must be a bounded opaque identifier");
    }
    Objects.requireNonNull(outcome, "source outcome");
    Objects.requireNonNull(freshness, "source freshness");
    quantity = Objects.requireNonNull(quantity, "quantity");
    state = Objects.requireNonNull(state, "state");
    unit = Objects.requireNonNull(unit, "unit");
    quantity.ifPresent(
        value -> {
          if (value.signum() < 0 || value.scale() > 6) {
            throw new IllegalArgumentException("quantity must be nonnegative and bounded");
          }
        });
    state.ifPresent(RemoteCapacityEvidence::requireBoundedValue);
    unit.ifPresent(RemoteCapacityEvidence::requireBoundedValue);
    Objects.requireNonNull(observedAt, "source observed time");
    Objects.requireNonNull(receivedAt, "local received time");
    Objects.requireNonNull(validUntil, "source validity deadline");
    if (!validUntil.isAfter(observedAt)) {
      throw new IllegalArgumentException("source validity deadline must follow observation time");
    }
  }

  public RemoteCapacityEvidence withSourceConflict(boolean conflicted) {
    return new RemoteCapacityEvidence(
        facility,
        dimensionCode,
        sourceSequence,
        dimensionVersion,
        policyVersion,
        sourceProfileVersion,
        evidenceId,
        outcome,
        freshness,
        complete,
        quantity,
        state,
        unit,
        observedAt,
        receivedAt,
        validUntil,
        conflicted);
  }

  private static void requireBoundedValue(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || !value.equals(value.strip())
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("source value must be bounded opaque text");
    }
  }
}
