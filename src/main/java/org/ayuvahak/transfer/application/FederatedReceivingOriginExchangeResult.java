package org.ayuvahak.transfer.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.AssessmentOutcome;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.RemoteAssessmentEvidence;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.RemoteOutcome;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.RemoteReservationStatus;

/** Bounded, validated business result from the receiving Transfer owner. */
public record FederatedReceivingOriginExchangeResult(
    RemoteOutcome outcome,
    RemoteReservationStatus reservationStatus,
    InternalId receivingRequestId,
    Optional<InternalId> capacityReservationId,
    Optional<RemoteAssessmentEvidence> clinicalAssessment,
    Optional<RemoteAssessmentEvidence> operationalAssessment,
    Optional<java.time.Instant> acceptedAt,
    java.time.Instant sourceUpdatedAt,
    long receivingVersion) {

  public FederatedReceivingOriginExchangeResult {
    Objects.requireNonNull(outcome, "remote outcome");
    Objects.requireNonNull(reservationStatus, "remote reservation status");
    Objects.requireNonNull(receivingRequestId, "receiving request reference");
    capacityReservationId = Objects.requireNonNull(capacityReservationId, "reservation reference");
    clinicalAssessment = Objects.requireNonNull(clinicalAssessment, "clinical assessment");
    operationalAssessment = Objects.requireNonNull(operationalAssessment, "operational assessment");
    acceptedAt = Objects.requireNonNull(acceptedAt, "acceptance time");
    Objects.requireNonNull(sourceUpdatedAt, "source update time");
    if (receivingVersion < 1) {
      throw new IllegalArgumentException("receiving version must be positive");
    }
    if ((outcome == RemoteOutcome.RECEIVED || outcome == RemoteOutcome.REJECTED)
        != (reservationStatus == RemoteReservationStatus.NOT_REQUESTED)) {
      throw new IllegalArgumentException("reservation state does not match receiving outcome");
    }
    boolean reservationExists =
        reservationStatus == RemoteReservationStatus.RESERVED
            || reservationStatus == RemoteReservationStatus.AT_RISK
            || reservationStatus == RemoteReservationStatus.RELEASED
            || reservationStatus == RemoteReservationStatus.EXPIRED;
    if (reservationExists != capacityReservationId.isPresent()) {
      throw new IllegalArgumentException("reservation reference does not match reservation state");
    }
    if ((outcome == RemoteOutcome.ACCEPTED_RESERVED)
        != (reservationStatus == RemoteReservationStatus.RESERVED)) {
      throw new IllegalArgumentException("acceptance outcome does not match reservation state");
    }
    if ((outcome == RemoteOutcome.ACCEPTED_RESERVED || outcome == RemoteOutcome.ACCEPTED_UNRESERVED)
        != acceptedAt.isPresent()) {
      throw new IllegalArgumentException("acceptance time does not match receiving outcome");
    }
    if (outcome == RemoteOutcome.ACCEPTED_RESERVED
        || outcome == RemoteOutcome.ACCEPTED_UNRESERVED) {
      requireSupportingAssessment(clinicalAssessment, "clinical");
      requireSupportingAssessment(operationalAssessment, "operational");
    }
  }

  private static void requireSupportingAssessment(
      Optional<RemoteAssessmentEvidence> assessment, String kind) {
    if (assessment.isEmpty() || assessment.orElseThrow().outcome() != AssessmentOutcome.SUPPORTS) {
      throw new IllegalArgumentException(
          "accepted result requires a positive " + kind + " assessment");
    }
  }
}
