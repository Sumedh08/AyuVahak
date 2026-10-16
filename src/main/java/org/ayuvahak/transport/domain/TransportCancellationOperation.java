package org.ayuvahak.transport.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Durable Transport-owned cancellation intent and its confirmed operational outcome. */
public record TransportCancellationOperation(
    InternalId id,
    InternalId transportRequestId,
    String reasonCode,
    State state,
    Optional<Outcome> outcome,
    Instant initiatedAt,
    Optional<Instant> resolvedAt,
    Optional<String> sourceNamespace,
    Optional<String> sourceObservationId,
    Optional<String> resolutionFingerprint,
    Optional<Instant> sourceOccurredAt,
    Optional<String> resolutionCorrelationId,
    Optional<InternalId> resolutionEventId,
    long version) {

  public TransportCancellationOperation {
    Objects.requireNonNull(id, "cancellation operation id");
    Objects.requireNonNull(transportRequestId, "Transport request id");
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw new IllegalArgumentException("cancellation reason code is invalid");
    }
    Objects.requireNonNull(state, "operation state");
    outcome = Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(initiatedAt, "initiation time");
    resolvedAt = Objects.requireNonNull(resolvedAt, "resolution time");
    sourceNamespace = Objects.requireNonNull(sourceNamespace, "source namespace");
    sourceObservationId = Objects.requireNonNull(sourceObservationId, "source observation id");
    resolutionFingerprint = Objects.requireNonNull(resolutionFingerprint, "resolution fingerprint");
    sourceOccurredAt = Objects.requireNonNull(sourceOccurredAt, "source occurrence time");
    resolutionCorrelationId =
        Objects.requireNonNull(resolutionCorrelationId, "resolution correlation id");
    resolutionEventId = Objects.requireNonNull(resolutionEventId, "resolution event id");
    if ((state == State.RESOLVED) != outcome.isPresent()
        || (state == State.RESOLVED) != resolvedAt.isPresent()
        || (state == State.RESOLVED) != sourceNamespace.isPresent()
        || (state == State.RESOLVED) != sourceObservationId.isPresent()
        || (state == State.RESOLVED) != resolutionFingerprint.isPresent()
        || (state == State.RESOLVED) != sourceOccurredAt.isPresent()
        || (state == State.RESOLVED) != resolutionCorrelationId.isPresent()
        || (state == State.RESOLVED) != resolutionEventId.isPresent()) {
      throw new IllegalArgumentException("resolved operation state and outcome must agree");
    }
    if (version < 1) {
      throw new IllegalArgumentException("cancellation operation version must be positive");
    }
  }

  public enum State {
    PENDING,
    OUTCOME_UNKNOWN,
    RECONCILIATION_REQUIRED,
    RESOLVED
  }

  public enum Outcome {
    CANCELLED_BEFORE_MOVEMENT,
    CANCEL_REJECTED_ACTIVE,
    MOVEMENT_ALREADY_STARTED
  }
}
