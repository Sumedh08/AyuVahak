package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Origin-owned durable identity and sourced outcome for one remote receiving attempt. */
public record FederatedOriginReceivingAttempt(
    InternalId id,
    InternalId journeyId,
    int attemptNumber,
    DeploymentId originDeployment,
    DeploymentId peerDeployment,
    PartnerKey partnerKey,
    int configurationVersion,
    InternalId operationId,
    String requestFingerprint,
    String commandFingerprint,
    byte[] requestBody,
    State state,
    Optional<RemoteOutcome> remoteOutcome,
    Optional<RemoteReservationStatus> remoteReservationStatus,
    Optional<InternalId> remoteReceivingRequestId,
    Optional<InternalId> remoteCapacityReservationId,
    Optional<RemoteAssessmentEvidence> remoteClinicalAssessment,
    Optional<RemoteAssessmentEvidence> remoteOperationalAssessment,
    Optional<Instant> remoteAcceptedAt,
    Optional<Instant> remoteSourceUpdatedAt,
    long remoteVersion,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public FederatedOriginReceivingAttempt {
    Objects.requireNonNull(id, "origin attempt id");
    Objects.requireNonNull(journeyId, "TransferJourney id");
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("destination attempt number must be positive");
    }
    Objects.requireNonNull(originDeployment, "origin deployment");
    Objects.requireNonNull(peerDeployment, "peer deployment");
    if (originDeployment.equals(peerDeployment)) {
      throw new IllegalArgumentException("origin and receiving deployments must differ");
    }
    Objects.requireNonNull(partnerKey, "partner key");
    if (configurationVersion < 1) {
      throw new IllegalArgumentException("partner configuration version must be positive");
    }
    Objects.requireNonNull(operationId, "stable operation id");
    new FederationOperationFingerprint(Objects.requireNonNull(requestFingerprint));
    new FederationOperationFingerprint(Objects.requireNonNull(commandFingerprint));
    requestBody = Objects.requireNonNull(requestBody, "request body").clone();
    if (requestBody.length == 0 || requestBody.length > 65_536) {
      throw new IllegalArgumentException("federated receiving request body is outside its bound");
    }
    Objects.requireNonNull(state, "operation state");
    remoteOutcome = Objects.requireNonNull(remoteOutcome, "remote outcome");
    remoteReservationStatus =
        Objects.requireNonNull(remoteReservationStatus, "remote reservation status");
    remoteReceivingRequestId =
        Objects.requireNonNull(remoteReceivingRequestId, "remote request id");
    remoteCapacityReservationId =
        Objects.requireNonNull(remoteCapacityReservationId, "remote reservation id");
    remoteClinicalAssessment =
        Objects.requireNonNull(remoteClinicalAssessment, "remote clinical assessment");
    remoteOperationalAssessment =
        Objects.requireNonNull(remoteOperationalAssessment, "remote operational assessment");
    remoteAcceptedAt = Objects.requireNonNull(remoteAcceptedAt, "remote acceptance time");
    remoteSourceUpdatedAt =
        Objects.requireNonNull(remoteSourceUpdatedAt, "remote source update time");
    if (remoteOutcome.isPresent()
        != (remoteReservationStatus.isPresent() && remoteReceivingRequestId.isPresent())) {
      throw new IllegalArgumentException("remote result provenance is incomplete");
    }
    if (remoteOutcome.isEmpty()
        && (remoteClinicalAssessment.isPresent()
            || remoteOperationalAssessment.isPresent()
            || remoteAcceptedAt.isPresent()
            || remoteCapacityReservationId.isPresent())) {
      throw new IllegalArgumentException("remote evidence requires a remote result");
    }
    if (remoteVersion < 0 || version < 1) {
      throw new IllegalArgumentException("remote/local versions are invalid");
    }
    Objects.requireNonNull(createdAt, "creation time");
    Objects.requireNonNull(updatedAt, "update time");
  }

  @Override
  public byte[] requestBody() {
    return requestBody.clone();
  }

  public enum State {
    PENDING,
    SENT,
    OUTCOME_UNKNOWN,
    RESOLVED,
    REJECTED,
    RECONCILIATION_REQUIRED
  }

  public enum RemoteOutcome {
    RECEIVED,
    ACCEPTED_RESERVED,
    ACCEPTED_UNRESERVED,
    REJECTED
  }

  public enum RemoteReservationStatus {
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

  /** Allowlisted remote decision evidence; actor identities and narratives are never copied. */
  public record RemoteAssessmentEvidence(
      AssessmentOutcome outcome, String reasonCode, Instant occurredAt, Instant recordedAt) {
    public RemoteAssessmentEvidence {
      Objects.requireNonNull(outcome, "assessment outcome");
      if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
        throw new IllegalArgumentException("assessment reason code is invalid");
      }
      Objects.requireNonNull(occurredAt, "assessment source time");
      Objects.requireNonNull(recordedAt, "assessment recorded time");
    }
  }

  public enum AssessmentOutcome {
    SUPPORTS,
    DOES_NOT_SUPPORT,
    INDETERMINATE
  }

  public static FederatedOriginReceivingAttempt pending(
      InternalId id,
      InternalId journeyId,
      int attemptNumber,
      DeploymentId originDeployment,
      DeploymentId peerDeployment,
      PartnerKey partnerKey,
      int configurationVersion,
      InternalId operationId,
      FederationOperationFingerprint requestFingerprint,
      String commandFingerprint,
      byte[] requestBody,
      Instant now) {
    return new FederatedOriginReceivingAttempt(
        id,
        journeyId,
        attemptNumber,
        originDeployment,
        peerDeployment,
        partnerKey,
        configurationVersion,
        operationId,
        requestFingerprint.value(),
        commandFingerprint,
        requestBody,
        State.PENDING,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        0,
        1,
        now,
        now);
  }

  public FederatedOriginReceivingAttempt withState(State next, Instant now) {
    return new FederatedOriginReceivingAttempt(
        id,
        journeyId,
        attemptNumber,
        originDeployment,
        peerDeployment,
        partnerKey,
        configurationVersion,
        operationId,
        requestFingerprint,
        commandFingerprint,
        requestBody,
        next,
        remoteOutcome,
        remoteReservationStatus,
        remoteReceivingRequestId,
        remoteCapacityReservationId,
        remoteClinicalAssessment,
        remoteOperationalAssessment,
        remoteAcceptedAt,
        remoteSourceUpdatedAt,
        remoteVersion,
        Math.addExact(version, 1),
        createdAt,
        now);
  }

  public FederatedOriginReceivingAttempt withRemoteResult(
      RemoteOutcome outcome,
      RemoteReservationStatus reservationStatus,
      InternalId receivingRequestId,
      Optional<InternalId> reservationId,
      Optional<RemoteAssessmentEvidence> clinicalAssessment,
      Optional<RemoteAssessmentEvidence> operationalAssessment,
      Optional<Instant> acceptedAt,
      Instant sourceUpdatedAt,
      long receivingVersion,
      Instant now) {
    Objects.requireNonNull(outcome, "remote outcome");
    Objects.requireNonNull(reservationStatus, "remote reservation status");
    Objects.requireNonNull(receivingRequestId, "remote receiving request id");
    reservationId = Objects.requireNonNull(reservationId, "remote reservation id");
    clinicalAssessment = Objects.requireNonNull(clinicalAssessment, "clinical assessment");
    operationalAssessment = Objects.requireNonNull(operationalAssessment, "operational assessment");
    acceptedAt = Objects.requireNonNull(acceptedAt, "remote acceptance time");
    Objects.requireNonNull(sourceUpdatedAt, "remote source update time");
    if (receivingVersion < remoteVersion) {
      throw new IllegalArgumentException("remote owner version cannot move backwards");
    }
    if (state == State.REJECTED) {
      throw new IllegalStateException("a terminal operation cannot accept a remote update");
    }
    boolean same =
        remoteOutcome.equals(Optional.of(outcome))
            && remoteReservationStatus.equals(Optional.of(reservationStatus))
            && remoteReceivingRequestId.equals(Optional.of(receivingRequestId))
            && remoteCapacityReservationId.equals(reservationId)
            && remoteClinicalAssessment.equals(clinicalAssessment)
            && remoteOperationalAssessment.equals(operationalAssessment)
            && remoteAcceptedAt.equals(acceptedAt)
            && remoteSourceUpdatedAt.equals(Optional.of(sourceUpdatedAt))
            && remoteVersion == receivingVersion;
    if (same) {
      return this;
    }
    if (remoteOutcome.isPresent() && receivingVersion == remoteVersion) {
      throw new IllegalArgumentException("remote owner changed evidence without advancing version");
    }
    State nextState =
        switch (outcome) {
          case RECEIVED -> State.SENT;
          case REJECTED -> State.REJECTED;
          case ACCEPTED_RESERVED -> State.RESOLVED;
          case ACCEPTED_UNRESERVED ->
              isRecoverable(reservationStatus) ? State.OUTCOME_UNKNOWN : State.RESOLVED;
        };
    return new FederatedOriginReceivingAttempt(
        id,
        journeyId,
        attemptNumber,
        originDeployment,
        peerDeployment,
        partnerKey,
        configurationVersion,
        operationId,
        requestFingerprint,
        commandFingerprint,
        requestBody,
        nextState,
        Optional.of(outcome),
        Optional.of(reservationStatus),
        Optional.of(receivingRequestId),
        reservationId,
        clinicalAssessment,
        operationalAssessment,
        acceptedAt,
        Optional.of(sourceUpdatedAt),
        receivingVersion,
        Math.addExact(version, 1),
        createdAt,
        now);
  }

  private static boolean isRecoverable(RemoteReservationStatus status) {
    return status == RemoteReservationStatus.PENDING
        || status == RemoteReservationStatus.OUTCOME_UNKNOWN
        || status == RemoteReservationStatus.UNKNOWN
        || status == RemoteReservationStatus.UNAVAILABLE;
  }
}
