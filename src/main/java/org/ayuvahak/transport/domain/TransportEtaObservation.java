package org.ayuvahak.transport.domain;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Immutable, freshness-bounded ETA evidence. An estimate does not assert arrival. */
public record TransportEtaObservation(
    InternalId id,
    InternalId transportRequestId,
    String sourceNamespace,
    String sourceObservationId,
    Instant estimatedArrivalAt,
    Instant observedAt,
    Instant recordedAt,
    Instant validUntil,
    ActorReference recordedBy,
    String authorityCode,
    ExternalReference organization,
    ExternalReference facility,
    CorrelationId correlationId) {

  public TransportEtaObservation {
    Objects.requireNonNull(id, "ETA observation id");
    Objects.requireNonNull(transportRequestId, "Transport request id");
    requireToken(sourceNamespace, "source namespace", 256);
    requireToken(sourceObservationId, "source observation id", 128);
    Objects.requireNonNull(estimatedArrivalAt, "estimated arrival time");
    Objects.requireNonNull(observedAt, "source observation time");
    Objects.requireNonNull(recordedAt, "recording time");
    Objects.requireNonNull(validUntil, "ETA validity deadline");
    if (!estimatedArrivalAt.isAfter(observedAt) || !validUntil.isAfter(recordedAt)) {
      throw new IllegalArgumentException("ETA must be future-facing and remain briefly observable");
    }
    Objects.requireNonNull(recordedBy, "recording actor");
    requireToken(authorityCode, "authority code", 64);
    requireDirectoryReference(organization, "Organization");
    requireDirectoryReference(facility, "Facility");
    Objects.requireNonNull(correlationId, "correlation id");
  }

  private static void requireDirectoryReference(ExternalReference reference, String type) {
    Objects.requireNonNull(reference, type + " reference");
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      throw new IllegalArgumentException("ETA scope must use Directory references");
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
}
