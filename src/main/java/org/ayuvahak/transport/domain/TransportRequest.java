package org.ayuvahak.transport.domain;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Transport-owned request references and attribution; it contains no patient or clinical data. */
public record TransportRequest(
    InternalId id,
    InternalId transferId,
    ExternalReference originFacility,
    ExternalReference destinationFacility,
    ActorReference initiatedBy,
    ActorReference recordedBy,
    Status status,
    Instant requestedAt,
    CorrelationId correlationId,
    long version) {

  public TransportRequest {
    Objects.requireNonNull(id, "Transport request id");
    Objects.requireNonNull(transferId, "Transfer id");
    requireFacility(originFacility, "origin Facility");
    requireFacility(destinationFacility, "destination Facility");
    Objects.requireNonNull(initiatedBy, "initiating actor");
    Objects.requireNonNull(recordedBy, "recording actor");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(requestedAt, "request time");
    Objects.requireNonNull(correlationId, "correlation id");
    if (version != 1) {
      throw new IllegalArgumentException("a new Transport request must start at version one");
    }
  }

  private static void requireFacility(ExternalReference reference, String name) {
    Objects.requireNonNull(reference, name);
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !"Facility".equals(reference.resourceType())) {
      throw new IllegalArgumentException(name + " must be a Directory Facility reference");
    }
    InternalId.parse(reference.value());
  }

  public enum Status {
    REQUESTED,
    AWAITING_ASSIGNMENT,
    ASSIGNED,
    EN_ROUTE_TO_ORIGIN,
    AT_ORIGIN,
    PATIENT_ONBOARD,
    EN_ROUTE_TO_DESTINATION,
    ARRIVED_DESTINATION,
    COMPLETED,
    CANCELLED,
    INTERRUPTED,
    UNFULFILLED
  }
}
