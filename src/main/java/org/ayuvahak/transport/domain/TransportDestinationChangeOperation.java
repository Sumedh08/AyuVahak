package org.ayuvahak.transport.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;

/** Provider-neutral Transport-owned request and concrete acknowledgement for a reroute. */
public record TransportDestinationChangeOperation(
    InternalId id,
    InternalId transferDestinationChangeId,
    InternalId transferId,
    InternalId transportRequestId,
    InternalId oldFacilityId,
    InternalId proposedFacilityId,
    String reasonCode,
    State state,
    Optional<Outcome> outcome,
    ActorReference initiatedBy,
    Optional<ActorReference> recordedBy,
    Optional<String> sourceNamespace,
    Optional<String> sourceObservationId,
    Optional<String> resolutionFingerprint,
    Optional<Instant> sourceOccurredAt,
    Optional<Instant> resolvedAt,
    Optional<InternalId> resolutionEventId,
    CorrelationId correlationId,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public TransportDestinationChangeOperation {
    Objects.requireNonNull(id, "operation id");
    Objects.requireNonNull(transferDestinationChangeId, "Transfer destination-change id");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(transportRequestId, "Transport request id");
    Objects.requireNonNull(oldFacilityId, "old destination Facility id");
    Objects.requireNonNull(proposedFacilityId, "proposed destination Facility id");
    if (oldFacilityId.equals(proposedFacilityId)) {
      throw new IllegalArgumentException("destination change must target another Facility");
    }
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw new IllegalArgumentException("destination-change reason code is invalid");
    }
    Objects.requireNonNull(state, "operation state");
    outcome = Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(initiatedBy, "initiating actor");
    recordedBy = Objects.requireNonNull(recordedBy, "recording actor");
    sourceNamespace = Objects.requireNonNull(sourceNamespace, "source namespace");
    sourceObservationId = Objects.requireNonNull(sourceObservationId, "source observation id");
    resolutionFingerprint = Objects.requireNonNull(resolutionFingerprint, "resolution fingerprint");
    sourceOccurredAt = Objects.requireNonNull(sourceOccurredAt, "source time");
    resolvedAt = Objects.requireNonNull(resolvedAt, "resolution time");
    resolutionEventId = Objects.requireNonNull(resolutionEventId, "resolution event id");
    Objects.requireNonNull(correlationId, "correlation id");
    if (version < 1 || updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("operation version/timestamps are invalid");
    }
    if ((state == State.RESOLVED) != outcome.isPresent()
        || (state == State.RESOLVED) != resolutionEventId.isPresent()) {
      throw new IllegalArgumentException("resolved state requires a concrete outcome and event");
    }
  }

  public enum State {
    PENDING,
    OUTCOME_UNKNOWN,
    RECONCILIATION_REQUIRED,
    RESOLVED
  }

  public enum Outcome {
    ACCEPTED,
    REJECTED
  }
}
