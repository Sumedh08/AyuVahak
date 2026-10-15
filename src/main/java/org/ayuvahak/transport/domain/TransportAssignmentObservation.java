package org.ayuvahak.transport.domain;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Immutable provider-neutral assignment evidence; the provider owns the assignment itself. */
public record TransportAssignmentObservation(
    InternalId id,
    InternalId transportRequestId,
    String sourceNamespace,
    String sourceObservationId,
    String assignmentReference,
    Instant occurredAt,
    Instant recordedAt,
    ActorReference recordedBy,
    String authorityCode,
    ExternalReference organization,
    ExternalReference facility,
    CorrelationId correlationId,
    long resultingRequestVersion) {

  public TransportAssignmentObservation {
    Objects.requireNonNull(id, "assignment observation id");
    Objects.requireNonNull(transportRequestId, "Transport request id");
    requireToken(sourceNamespace, "source namespace", 256);
    requireToken(sourceObservationId, "source observation id", 128);
    requireToken(assignmentReference, "assignment reference", 256);
    Objects.requireNonNull(occurredAt, "source occurrence time");
    Objects.requireNonNull(recordedAt, "recording time");
    Objects.requireNonNull(recordedBy, "recording actor");
    requireToken(authorityCode, "authority code", 64);
    requireDirectoryReference(organization, "Organization");
    requireDirectoryReference(facility, "Facility");
    Objects.requireNonNull(correlationId, "correlation id");
    if (resultingRequestVersion < 2) {
      throw new IllegalArgumentException("assignment must advance the request version");
    }
  }

  private static void requireDirectoryReference(ExternalReference reference, String type) {
    Objects.requireNonNull(reference, type + " reference");
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      throw new IllegalArgumentException("assignment scope must use Directory references");
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
