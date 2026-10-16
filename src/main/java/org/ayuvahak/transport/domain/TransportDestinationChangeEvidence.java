package org.ayuvahak.transport.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;

/** Minimized immutable source evidence for a Transport destination-change acknowledgement. */
public record TransportDestinationChangeEvidence(
    InternalId id,
    InternalId operationId,
    TransportDestinationChangeOperation.Outcome reportedOutcome,
    String sourceNamespace,
    String sourceObservationId,
    String fingerprint,
    Instant sourceOccurredAt,
    ActorReference recordedBy,
    Instant recordedAt,
    Disposition disposition,
    Optional<String> reasonCode,
    CorrelationId correlationId) {

  public TransportDestinationChangeEvidence {
    Objects.requireNonNull(id, "evidence id");
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(reportedOutcome, "reported outcome");
    if (sourceNamespace == null
        || !sourceNamespace.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")
        || sourceObservationId == null
        || !sourceObservationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("source identity is invalid");
    }
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("evidence fingerprint is invalid");
    }
    Objects.requireNonNull(sourceOccurredAt, "source occurrence time");
    Objects.requireNonNull(recordedBy, "recording actor");
    Objects.requireNonNull(recordedAt, "recorded time");
    Objects.requireNonNull(disposition, "evidence disposition");
    reasonCode = Objects.requireNonNull(reasonCode, "reason code");
    Objects.requireNonNull(correlationId, "correlation id");
    if ((disposition == Disposition.RECONCILIATION_REQUIRED) != reasonCode.isPresent()) {
      throw new IllegalArgumentException(
          "reconciliation evidence requires a reason and resolved evidence does not");
    }
    reasonCode.ifPresent(
        reason -> {
          if (!reason.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("evidence reason code is invalid");
          }
        });
  }

  public enum Disposition {
    RESOLVED,
    RECONCILIATION_REQUIRED
  }
}
