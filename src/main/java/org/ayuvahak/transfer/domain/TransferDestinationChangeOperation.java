package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Transfer-owned, privacy-minimal intent for a post-movement destination change. */
public record TransferDestinationChangeOperation(
    InternalId id,
    InternalId transferId,
    InternalId transportRequestId,
    ExternalReference oldOrganization,
    ExternalReference oldFacility,
    ExternalReference proposedOrganization,
    ExternalReference proposedFacility,
    InternalId recommendationId,
    long referralRevision,
    String reasonCode,
    java.util.Optional<DestinationChangeReservationPlan> reservationPlan,
    Status status,
    InternalId capacityReservationOperationId,
    CapacityOutcome capacityReservationOutcome,
    InternalId oldReservationOperationId,
    InternalId oldHoldReleaseOperationId,
    InternalId proposedHoldReleaseOperationId,
    InternalId transportOperationId,
    Optional<ProviderOutcome> providerOutcome,
    ActorReference initiatedBy,
    ExternalReference originOrganization,
    ExternalReference originFacility,
    CorrelationId correlationId,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public TransferDestinationChangeOperation {
    Objects.requireNonNull(id, "destination-change operation id");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(transportRequestId, "Transport request id");
    requireFacility(oldFacility, "old Facility");
    requireFacility(proposedFacility, "proposed Facility");
    requireOrganization(oldOrganization, "old Organization");
    requireOrganization(proposedOrganization, "proposed Organization");
    Objects.requireNonNull(recommendationId, "recommendation id");
    if (referralRevision < 1) {
      throw new IllegalArgumentException("Referral revision must be positive");
    }
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw new IllegalArgumentException("destination-change reason code is invalid");
    }
    Objects.requireNonNull(status, "destination-change status");
    reservationPlan = Objects.requireNonNull(reservationPlan, "Capacity reservation plan");
    Objects.requireNonNull(capacityReservationOperationId, "new Capacity operation id");
    Objects.requireNonNull(capacityReservationOutcome, "Capacity outcome");
    Objects.requireNonNull(oldReservationOperationId, "old Capacity operation id");
    Objects.requireNonNull(oldHoldReleaseOperationId, "old-hold release operation id");
    Objects.requireNonNull(proposedHoldReleaseOperationId, "proposed-hold release operation id");
    Objects.requireNonNull(transportOperationId, "Transport operation id");
    providerOutcome = Objects.requireNonNull(providerOutcome, "provider outcome");
    Objects.requireNonNull(initiatedBy, "initiating actor");
    requireOrganization(originOrganization, "origin Organization");
    requireFacility(originFacility, "origin Facility");
    Objects.requireNonNull(correlationId, "correlation id");
    if (version < 1) {
      throw new IllegalArgumentException("operation version must be positive");
    }
    Objects.requireNonNull(createdAt, "created at");
    Objects.requireNonNull(updatedAt, "updated at");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt cannot precede createdAt");
    }
    if (capacityReservationOperationId.equals(oldReservationOperationId)) {
      throw new IllegalArgumentException("new and old Capacity operation identities must differ");
    }
    if (oldHoldReleaseOperationId.equals(proposedHoldReleaseOperationId)) {
      throw new IllegalArgumentException("Capacity release identities must be distinct");
    }
  }

  private static void requireFacility(ExternalReference reference, String name) {
    Objects.requireNonNull(reference, name);
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !"Facility".equals(reference.resourceType())) {
      throw new IllegalArgumentException(name + " must be a Directory Facility");
    }
    InternalId.parse(reference.value());
  }

  private static void requireOrganization(ExternalReference reference, String name) {
    Objects.requireNonNull(reference, name);
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !"Organization".equals(reference.resourceType())) {
      throw new IllegalArgumentException(name + " must be a Directory Organization");
    }
    InternalId.parse(reference.value());
  }

  public enum Status {
    AWAITING_RECEIVER_DECISIONS,
    CAPACITY_RESERVATION_PENDING,
    CAPACITY_RESERVATION_UNKNOWN,
    TRANSPORT_ACK_PENDING,
    TRANSPORT_OUTCOME_UNKNOWN,
    OLD_HOLD_RELEASE_PENDING,
    PROPOSED_HOLD_RELEASE_PENDING,
    RECONCILIATION_REQUIRED,
    RESOLVED
  }

  public enum CapacityOutcome {
    PENDING,
    RESERVED,
    CONFLICT,
    UNKNOWN,
    UNAVAILABLE,
    INVALID,
    UNCONFIGURED
  }

  public enum ProviderOutcome {
    ACCEPTED,
    REJECTED,
    OUTCOME_UNKNOWN,
    RECONCILIATION_REQUIRED
  }
}
