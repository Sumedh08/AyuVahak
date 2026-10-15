package org.ayuvahak.transport.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Immutable, attributed source evidence about an operational Transport status. */
public record TransportStatusObservation(
    InternalId id,
    InternalId transportRequestId,
    String sourceNamespace,
    String sourceObservationId,
    OptionalLong sourceSequence,
    TransportRequest.Status status,
    Instant occurredAt,
    Instant recordedAt,
    ActorReference recordedBy,
    String authorityCode,
    ExternalReference organization,
    ExternalReference facility,
    CorrelationId correlationId,
    Outcome outcome,
    TransportRequest.Status requestStatusAfter,
    Optional<String> reasonCode,
    long resultingRequestVersion) {

  public TransportStatusObservation {
    Objects.requireNonNull(id, "status observation id");
    Objects.requireNonNull(transportRequestId, "Transport request id");
    requireToken(sourceNamespace, "source namespace", 256);
    requireToken(sourceObservationId, "source observation id", 128);
    Objects.requireNonNull(sourceSequence, "source sequence");
    sourceSequence.ifPresent(
        sequence -> {
          if (sequence < 0) {
            throw new IllegalArgumentException("source sequence cannot be negative");
          }
        });
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(occurredAt, "source occurrence time");
    Objects.requireNonNull(recordedAt, "recording time");
    Objects.requireNonNull(recordedBy, "recording actor");
    requireToken(authorityCode, "authority code", 64);
    requireDirectoryReference(organization, "Organization");
    requireDirectoryReference(facility, "Facility");
    Objects.requireNonNull(correlationId, "correlation id");
    Objects.requireNonNull(outcome, "observation outcome");
    Objects.requireNonNull(requestStatusAfter, "request status after observation");
    reasonCode = Objects.requireNonNull(reasonCode, "reason code");
    reasonCode.ifPresent(value -> requireToken(value, "reason code", 80));
    if (resultingRequestVersion < 1) {
      throw new IllegalArgumentException("resulting request version must be positive");
    }
  }

  private static void requireDirectoryReference(ExternalReference reference, String type) {
    Objects.requireNonNull(reference, type + " reference");
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      throw new IllegalArgumentException("status observation scope must use Directory references");
    }
    InternalId.parse(reference.value());
  }

  private static void requireToken(String value, String label, int maxLength) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > maxLength
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          label + " must be bounded and contain no control characters");
    }
  }

  public enum Outcome {
    APPLIED,
    RECONCILIATION_REQUIRED,
    FUTURE_SKEWED
  }
}
