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
    Optional<InternalId> remoteReceivingRequestId,
    Optional<InternalId> remoteCapacityReservationId,
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
    remoteReceivingRequestId =
        Objects.requireNonNull(remoteReceivingRequestId, "remote request id");
    remoteCapacityReservationId =
        Objects.requireNonNull(remoteCapacityReservationId, "remote reservation id");
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
        remoteReceivingRequestId,
        remoteCapacityReservationId,
        remoteVersion,
        Math.addExact(version, 1),
        createdAt,
        now);
  }

  public FederatedOriginReceivingAttempt withRemoteResult(
      RemoteOutcome outcome,
      InternalId receivingRequestId,
      Optional<InternalId> reservationId,
      long receivingVersion,
      Instant now) {
    Objects.requireNonNull(outcome, "remote outcome");
    Objects.requireNonNull(receivingRequestId, "remote receiving request id");
    reservationId = Objects.requireNonNull(reservationId, "remote reservation id");
    if (receivingVersion < remoteVersion) {
      throw new IllegalArgumentException("remote owner version cannot move backwards");
    }
    if (state == State.REJECTED) {
      throw new IllegalStateException("a terminal operation cannot accept a remote update");
    }
    State nextState = outcome == RemoteOutcome.REJECTED ? State.REJECTED : State.RESOLVED;
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
        Optional.of(receivingRequestId),
        reservationId,
        receivingVersion,
        Math.addExact(version, 1),
        createdAt,
        now);
  }
}
