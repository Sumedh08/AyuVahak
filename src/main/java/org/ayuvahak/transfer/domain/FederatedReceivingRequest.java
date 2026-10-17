package org.ayuvahak.transfer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Receiver-owned coordination case; its remote Transfer reference is never a local aggregate. */
public record FederatedReceivingRequest(
    InternalId id,
    DeploymentId originDeployment,
    InternalId requestOperationId,
    String requestFingerprint,
    InternalId originJourneyId,
    String originFacilityId,
    ExternalReference destinationOrganization,
    ExternalReference destinationFacility,
    String capacityDimensionCode,
    String capacityUnit,
    BigDecimal capacityQuantity,
    Instant capacityIntervalStart,
    Instant capacityIntervalEnd,
    Status status,
    Optional<Assessment> clinicalAssessment,
    Optional<Assessment> operationalAssessment,
    Optional<ActorReference> acceptedBy,
    Optional<Instant> acceptedAt,
    Optional<InternalId> capacityOperationId,
    ReservationStatus reservationStatus,
    Optional<InternalId> capacityReservationId,
    Optional<String> capacityReasonCode,
    long capacityLifecycleSequence,
    long version,
    Instant receivedAt,
    Instant updatedAt) {

  public FederatedReceivingRequest {
    Objects.requireNonNull(id, "receiving request id");
    Objects.requireNonNull(originDeployment, "origin deployment");
    Objects.requireNonNull(requestOperationId, "request operation id");
    if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("request fingerprint must be a SHA-256 digest");
    }
    Objects.requireNonNull(originJourneyId, "origin TransferJourney reference");
    if (originFacilityId == null
        || !originFacilityId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("origin Facility reference is invalid");
    }
    requireDirectoryReference(destinationOrganization, "Organization");
    requireDirectoryReference(destinationFacility, "Facility");
    Objects.requireNonNull(capacityDimensionCode, "capacity dimension");
    Objects.requireNonNull(capacityUnit, "capacity unit");
    Objects.requireNonNull(capacityQuantity, "capacity quantity");
    if (capacityQuantity.signum() <= 0) {
      throw new IllegalArgumentException("capacity quantity must be positive");
    }
    Objects.requireNonNull(capacityIntervalStart, "capacity interval start");
    Objects.requireNonNull(capacityIntervalEnd, "capacity interval end");
    if (!capacityIntervalEnd.isAfter(capacityIntervalStart)) {
      throw new IllegalArgumentException("capacity interval end must be after its start");
    }
    Objects.requireNonNull(status, "receiving status");
    clinicalAssessment = Objects.requireNonNull(clinicalAssessment, "clinical assessment");
    operationalAssessment = Objects.requireNonNull(operationalAssessment, "operational assessment");
    acceptedBy = Objects.requireNonNull(acceptedBy, "acceptance actor");
    acceptedAt = Objects.requireNonNull(acceptedAt, "acceptance time");
    capacityOperationId = Objects.requireNonNull(capacityOperationId, "Capacity operation id");
    Objects.requireNonNull(reservationStatus, "reservation status");
    capacityReservationId =
        Objects.requireNonNull(capacityReservationId, "Capacity reservation id");
    capacityReasonCode = Objects.requireNonNull(capacityReasonCode, "Capacity reason code");
    if ((reservationStatus == ReservationStatus.NOT_REQUESTED) != capacityOperationId.isEmpty()) {
      throw new IllegalArgumentException("Capacity operation identity and state are inconsistent");
    }
    if ((reservationStatus == ReservationStatus.RESERVED
            || reservationStatus == ReservationStatus.AT_RISK
            || reservationStatus == ReservationStatus.RELEASED
            || reservationStatus == ReservationStatus.EXPIRED)
        != capacityReservationId.isPresent()) {
      throw new IllegalArgumentException(
          "Capacity reservation reference and state are inconsistent");
    }
    if (capacityLifecycleSequence < 0 || version < 1) {
      throw new IllegalArgumentException("receiving version and Capacity sequence are invalid");
    }
    Objects.requireNonNull(receivedAt, "received time");
    Objects.requireNonNull(updatedAt, "updated time");
  }

  public enum Status {
    RECEIVED,
    ACCEPTED_UNRESERVED,
    ACCEPTED_RESERVED,
    REJECTED
  }

  public enum ReservationStatus {
    NOT_REQUESTED,
    PENDING,
    OUTCOME_UNKNOWN,
    RESERVED,
    CONFLICT,
    UNKNOWN,
    INVALID,
    UNAVAILABLE,
    AT_RISK,
    RELEASED,
    EXPIRED
  }

  public record Assessment(
      AssessmentKind kind,
      Outcome outcome,
      String reasonCode,
      ActorReference actor,
      Instant occurredAt,
      Instant recordedAt) {
    public Assessment {
      Objects.requireNonNull(kind, "assessment kind");
      Objects.requireNonNull(outcome, "assessment outcome");
      if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
        throw new IllegalArgumentException("assessment reason code is invalid");
      }
      Objects.requireNonNull(actor, "assessment actor");
      Objects.requireNonNull(occurredAt, "assessment occurrence time");
      Objects.requireNonNull(recordedAt, "assessment recorded time");
    }
  }

  public enum AssessmentKind {
    CLINICAL,
    OPERATIONAL
  }

  public enum Outcome {
    SUPPORTS,
    DOES_NOT_SUPPORT,
    INDETERMINATE
  }

  public FederatedReceivingRequest withAssessment(Assessment assessment) {
    Objects.requireNonNull(assessment, "assessment");
    return new FederatedReceivingRequest(
        id,
        originDeployment,
        requestOperationId,
        requestFingerprint,
        originJourneyId,
        originFacilityId,
        destinationOrganization,
        destinationFacility,
        capacityDimensionCode,
        capacityUnit,
        capacityQuantity,
        capacityIntervalStart,
        capacityIntervalEnd,
        status,
        assessment.kind() == AssessmentKind.CLINICAL ? Optional.of(assessment) : clinicalAssessment,
        assessment.kind() == AssessmentKind.OPERATIONAL
            ? Optional.of(assessment)
            : operationalAssessment,
        acceptedBy,
        acceptedAt,
        capacityOperationId,
        reservationStatus,
        capacityReservationId,
        capacityReasonCode,
        capacityLifecycleSequence,
        Math.addExact(version, 1),
        receivedAt,
        assessment.recordedAt());
  }

  public FederatedReceivingRequest accept(
      ActorReference actor, Instant acceptedAt, InternalId stableCapacityOperationId) {
    Objects.requireNonNull(actor, "acceptance actor");
    Objects.requireNonNull(acceptedAt, "acceptance time");
    Objects.requireNonNull(stableCapacityOperationId, "Capacity operation id");
    return new FederatedReceivingRequest(
        id,
        originDeployment,
        requestOperationId,
        requestFingerprint,
        originJourneyId,
        originFacilityId,
        destinationOrganization,
        destinationFacility,
        capacityDimensionCode,
        capacityUnit,
        capacityQuantity,
        capacityIntervalStart,
        capacityIntervalEnd,
        Status.ACCEPTED_UNRESERVED,
        clinicalAssessment,
        operationalAssessment,
        Optional.of(actor),
        Optional.of(acceptedAt),
        Optional.of(stableCapacityOperationId),
        ReservationStatus.PENDING,
        Optional.empty(),
        Optional.empty(),
        capacityLifecycleSequence,
        Math.addExact(version, 1),
        receivedAt,
        acceptedAt);
  }

  public FederatedReceivingRequest withCapacityOutcome(
      ReservationStatus outcome,
      Optional<InternalId> reservationId,
      Optional<String> reasonCode,
      long lifecycleSequence,
      Instant recordedAt) {
    Objects.requireNonNull(outcome, "reservation outcome");
    Objects.requireNonNull(reservationId, "reservation id");
    Objects.requireNonNull(reasonCode, "reason code");
    Objects.requireNonNull(recordedAt, "recorded time");
    if (capacityOperationId.isEmpty() || outcome == ReservationStatus.NOT_REQUESTED) {
      throw new IllegalStateException("Capacity outcome requires an accepted receiving request");
    }
    if (lifecycleSequence < capacityLifecycleSequence) {
      throw new IllegalArgumentException("Capacity lifecycle sequence cannot move backwards");
    }
    Status nextStatus =
        outcome == ReservationStatus.RESERVED
            ? Status.ACCEPTED_RESERVED
            : Status.ACCEPTED_UNRESERVED;
    return new FederatedReceivingRequest(
        id,
        originDeployment,
        requestOperationId,
        requestFingerprint,
        originJourneyId,
        originFacilityId,
        destinationOrganization,
        destinationFacility,
        capacityDimensionCode,
        capacityUnit,
        capacityQuantity,
        capacityIntervalStart,
        capacityIntervalEnd,
        nextStatus,
        clinicalAssessment,
        operationalAssessment,
        acceptedBy,
        acceptedAt,
        capacityOperationId,
        outcome,
        reservationId,
        reasonCode,
        lifecycleSequence,
        Math.addExact(version, 1),
        receivedAt,
        recordedAt);
  }

  private static void requireDirectoryReference(ExternalReference reference, String type) {
    Objects.requireNonNull(reference, type + " reference");
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      throw new IllegalArgumentException("receiving destination must use a Directory " + type);
    }
    InternalId.parse(reference.value());
  }
}
