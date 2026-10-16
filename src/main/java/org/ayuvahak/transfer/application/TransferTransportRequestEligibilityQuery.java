package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transfer.domain.CapacityReservationIntent;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferDecision;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Transfer-owned, current-state eligibility check for a linked Transport request. */
@Service
public final class TransferTransportRequestEligibilityQuery {

  private final TransferStore transfers;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransferSettings properties;
  private final Clock clock;

  public TransferTransportRequestEligibilityQuery(
      TransferStore transfers,
      AuthorizationService authorization,
      AuditWriter audit,
      TransferSettings properties,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.transfers = Objects.requireNonNull(transfers, "Transfer store");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.properties = Objects.requireNonNull(properties, "Transfer properties");
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  /**
   * Must run in the Transport request transaction: the Transfer rows are locked until the Transport
   * request commits, preventing a concurrent Transfer state change between check/write.
   */
  public TransferTransportEligibilityResult check(
      ExecutionContext context, InternalId transferId, ExternalReference requestedOrigin) {
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(requestedOrigin, "requested origin");
    if (!isConfiguredTransportWorker(context)) {
      return denied(context, transferId, "SERVICE_IDENTITY_INVALID");
    }
    AuthorizationDecision decision =
        authorization.evaluate(context, TransferAuthorities.transportRequestEligibility().code());
    if (!decision.isAllowed()) {
      String reason =
          decision.denialReason().orElse(null)
                  == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
              ? "AUTHORIZATION_STORE_UNAVAILABLE"
              : "AUTHORIZATION_DENIED";
      var denied = denied(context, transferId, reason);
      return reason.equals("AUTHORIZATION_STORE_UNAVAILABLE") ? unavailable() : denied;
    }

    try {
      Optional<TransferProgressTarget> target = transfers.lockProgressTarget(transferId);
      if (target.isEmpty()) {
        return denied(context, transferId, "TRANSFER_NOT_ELIGIBLE");
      }
      TransferProgressTarget progress = target.orElseThrow();
      Optional<TransferJourney> journeyValue =
          transfers.findJourney(progress.transfer().journeyId());
      Optional<TransferRequestAttempt> attemptValue =
          transfers.findRequestAttempt(progress.transfer().requestAttemptId());
      Optional<CapacityReservationIntent> reservationValue =
          transfers.findReservationIntent(transferId);
      if (journeyValue.isEmpty() || attemptValue.isEmpty() || reservationValue.isEmpty()) {
        return denied(context, transferId, "TRANSFER_STATE_INCOMPLETE");
      }
      TransferJourney journey = journeyValue.orElseThrow();
      TransferRequestAttempt attempt = attemptValue.orElseThrow();
      CapacityReservationIntent reservation = reservationValue.orElseThrow();
      Optional<TransferRequestAttempt> latest = transfers.findLatestRequest(journey.id());
      Optional<TransferStore.DecisionResult> decisionResult =
          transfers.findDecisionByVersion(attempt.id(), attempt.version());
      boolean eligible =
          journey.transportRequired()
              && journey.originFacility().filter(requestedOrigin::equals).isPresent()
              && progress.originFacility().filter(requestedOrigin::equals).isPresent()
              && context
                  .scope()
                  .organization()
                  .filter(progress.originOrganization()::equals)
                  .isPresent()
              && context.scope().facility().filter(requestedOrigin::equals).isPresent()
              && attempt.status() == TransferRequestAttempt.Status.ACCEPTED
              && latest.filter(item -> item.id().equals(attempt.id())).isPresent()
              && progress.transfer().requestAttemptId().equals(attempt.id())
              && progress.transfer().status() == CoordinatedTransfer.Status.READY
              && transfers.findJourneyClosure(journey.id()).isEmpty()
              && decisionResult
                  .filter(
                      result ->
                          result.decision().kind() == TransferDecision.Kind.ACCEPTED
                              && result
                                  .decision()
                                  .transferId()
                                  .filter(transferId::equals)
                                  .isPresent()
                              && result
                                  .transfer()
                                  .map(CoordinatedTransfer::id)
                                  .filter(transferId::equals)
                                  .isPresent())
                  .isPresent()
              && reservation.transferId().equals(transferId)
              && reservation.outcome() == CapacityReservationIntent.Outcome.RESERVED
              && reservation.reservationId().isPresent()
              && reservation.facility().equals(progress.receivingFacility())
              && reservation.expiresAt().filter(value -> value.isAfter(clock.instant())).isPresent()
              && progress.transfer().journeyId().equals(journey.id())
              && progress.receivingFacility().equals(attempt.destinationFacility());
      if (!eligible) {
        return denied(context, transferId, "TRANSFER_NOT_ELIGIBLE");
      }
      return new TransferTransportEligibilityResult(
          TransferTransportEligibilityResult.Outcome.ELIGIBLE,
          Optional.of(requestedOrigin),
          Optional.of(progress.receivingFacility()),
          Optional.empty());
    } catch (DataAccessException unavailable) {
      return unavailable();
    }
  }

  private boolean isConfiguredTransportWorker(ExecutionContext context) {
    return context != null
        && context.actor().kind() == ActorKind.SERVICE
        && context.actor().identity().value().equals(properties.getTransportWorkerClientId())
        && context.authenticationMechanism() == AuthenticationMechanism.CONFIGURED_SERVICE_IDENTITY
        && context.scope().kind() == ExecutionScope.Kind.FACILITY
        && context.scope().organization().isPresent()
        && context.scope().facility().isPresent();
  }

  private TransferTransportEligibilityResult denied(
      ExecutionContext context, InternalId transferId, String reason) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_TRANSPORT_REQUEST_ELIGIBILITY_DENIED",
              "Transfer",
              Optional.of(transferId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(reason),
              AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
              AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transfer withheld the eligibility result because its audit is unavailable.");
    }
    return new TransferTransportEligibilityResult(
        TransferTransportEligibilityResult.Outcome.INELIGIBLE,
        Optional.empty(),
        Optional.empty(),
        Optional.of(reason));
  }

  private static TransferTransportEligibilityResult unavailable() {
    return new TransferTransportEligibilityResult(
        TransferTransportEligibilityResult.Outcome.UNAVAILABLE,
        Optional.empty(),
        Optional.empty(),
        Optional.of("TRANSFER_STATE_UNAVAILABLE"));
  }
}
