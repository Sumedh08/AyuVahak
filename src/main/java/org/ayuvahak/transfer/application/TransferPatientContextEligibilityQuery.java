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
import org.ayuvahak.platform.audit.domain.AuditTargetContext;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferDecision;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/** Transfer-owned participant check; it exposes no Referral or patient content. */
@Service
public final class TransferPatientContextEligibilityQuery {

  private static final String ACTION_CODE = "TRANSFER_PATIENT_CONTEXT_PARTICIPATION_CHECK";

  private final TransferStore transfers;
  private final AuthorizationService authorization;
  private final AuditWriter auditWriter;
  private final TransactionTemplate ownerRead;
  private final Clock clock;

  public TransferPatientContextEligibilityQuery(
      TransferStore transfers,
      AuthorizationService authorization,
      AuditWriter auditWriter,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.transfers = Objects.requireNonNull(transfers, "Transfer store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.ownerRead = new TransactionTemplate(transactionManager);
    this.ownerRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.ownerRead.setReadOnly(true);
    this.ownerRead.setTimeout(5);
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  /**
   * Returns an eligibility-only proof for the exact active request and facility, or empty when the
   * requester is not a current participant. Authorization-store and owner-store failures are
   * surfaced as unavailable; callers must fail closed.
   */
  public Optional<PatientContextParticipation> verify(
      ExecutionContext context,
      InternalId referralId,
      InternalId transferId,
      InternalId requestAttemptId) {
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(requestAttemptId, "request attempt id");
    requireFacilityScope(context);

    AuthorizationDecision decision =
        authorization.evaluate(context, TransferAuthorities.patientContextParticipation().code());
    if (!decision.isAllowed()) {
      recordDenied(context, transferId, authorizationFailure(decision));
      if (decision.denialReason().orElse(null)
          == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
        throw problem(
            PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
            "Transfer could not authorize the patient-context participation check.");
      }
      return Optional.empty();
    }

    Optional<PatientContextParticipation> participation;
    try {
      participation =
          Objects.requireNonNull(
              ownerRead.execute(
                  ignored ->
                      readCurrentParticipation(context, referralId, transferId, requestAttemptId)),
              "Transfer participant read result");
    } catch (DataAccessException | TransactionTimedOutException unavailable) {
      throw problem(
          PlatformErrorCode.TRANSFER_UNAVAILABLE,
          "Transfer could not verify the selected patient-context participant safely.");
    }

    if (participation.isEmpty()) {
      recordDenied(context, transferId, "NOT_CURRENT_PARTICIPANT");
      return Optional.empty();
    }

    recordAllowed(context, transferId);
    return participation;
  }

  private Optional<PatientContextParticipation> readCurrentParticipation(
      ExecutionContext context,
      InternalId referralId,
      InternalId transferId,
      InternalId requestAttemptId) {
    Optional<TransferRequestAttempt> foundAttempt = transfers.findRequestAttempt(requestAttemptId);
    if (foundAttempt.isEmpty()) {
      return Optional.empty();
    }
    TransferRequestAttempt attempt = foundAttempt.orElseThrow();
    if (!attempt.id().equals(requestAttemptId)
        || !attempt.referralId().equals(referralId)
        || attempt.status() != TransferRequestAttempt.Status.ACCEPTED
        || !facilityMatches(context.scope(), attempt.destinationFacility())) {
      return Optional.empty();
    }

    Optional<TransferJourney> journeyResult = transfers.findJourney(attempt.journeyId());
    if (journeyResult.isEmpty()) {
      return Optional.empty();
    }
    TransferJourney journey = journeyResult.orElseThrow();
    if (!journey.id().equals(attempt.journeyId())
        || !journey.referralId().equals(referralId)
        || transfers.findJourneyClosure(journey.id()).isPresent()) {
      return Optional.empty();
    }

    Optional<TransferRequestAttempt> latest = transfers.findLatestRequest(journey.id());
    if (latest.isEmpty() || !latest.orElseThrow().id().equals(requestAttemptId)) {
      return Optional.empty();
    }

    Optional<TransferStore.DecisionResult> decisionResult =
        transfers.findDecisionByVersion(attempt.id(), attempt.version());
    if (decisionResult.isEmpty()) {
      return Optional.empty();
    }
    TransferStore.DecisionResult decision = decisionResult.orElseThrow();
    if (decision.decision().kind() != TransferDecision.Kind.ACCEPTED
        || !decision.decision().requestAttemptId().equals(requestAttemptId)
        || decision.decision().transferId().filter(transferId::equals).isEmpty()
        || !decision.decision().receivingFacility().equals(attempt.destinationFacility())
        || !decision.decision().receivingFacility().equals(context.scope().facility().orElseThrow())
        || !decision
            .decision()
            .receivingOrganization()
            .equals(context.scope().organization().orElseThrow())
        || decision.transfer().filter(accepted -> accepted.id().equals(transferId)).isEmpty()) {
      return Optional.empty();
    }

    Optional<CoordinatedTransfer> currentTransfer = transfers.findTransfer(transferId);
    if (currentTransfer.isEmpty()) {
      return Optional.empty();
    }
    CoordinatedTransfer transfer = currentTransfer.orElseThrow();
    if (!transfer.id().equals(transferId)
        || !transfer.journeyId().equals(journey.id())
        || !transfer.requestAttemptId().equals(requestAttemptId)
        || !isPreArrival(transfer.status())) {
      return Optional.empty();
    }

    return Optional.of(
        new PatientContextParticipation(
            referralId,
            transferId,
            requestAttemptId,
            attempt.referralRevision(),
            attempt.destinationFacility()));
  }

  private static boolean isPreArrival(CoordinatedTransfer.Status status) {
    return switch (status) {
      case AWAITING_RESERVATION, READY, COORDINATION_EXCEPTION, IN_TRANSIT, INTERRUPTED -> true;
      case CANCELLATION_PENDING_RELEASE, ARRIVED, HANDED_OVER, COMPLETED, CANCELLED -> false;
    };
  }

  private static boolean facilityMatches(ExecutionScope scope, ExternalReference facility) {
    return scope.kind() == ExecutionScope.Kind.FACILITY
        && scope.facility().filter(facility::equals).isPresent();
  }

  private void recordAllowed(ExecutionContext context, InternalId transferId) {
    record(
        context,
        transferId,
        AuditActionKind.SENSITIVE_READ,
        AuditOutcome.SUCCEEDED,
        AuditAuthorizationOutcome.ALLOWED,
        Optional.of("CURRENT_RECEIVING_PARTICIPANT"));
  }

  private void recordDenied(ExecutionContext context, InternalId transferId, String reasonCode) {
    record(
        context,
        transferId,
        AuditActionKind.SECURITY_OUTCOME,
        AuditOutcome.DENIED,
        AuditAuthorizationOutcome.DENIED,
        Optional.of(reasonCode));
  }

  private void record(
      ExecutionContext context,
      InternalId transferId,
      AuditActionKind kind,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome,
      Optional<String> reasonCode) {
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              kind,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              ACTION_CODE,
              "Transfer",
              Optional.of(transferId),
              clock.instant(),
              outcome,
              reasonCode,
              authentication(context.actor().kind()),
              authorizationOutcome,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(
                      context.scope().kind(),
                      context.scope().organization(),
                      context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transfer withheld patient-context participation because its audit is unavailable.");
    }
  }

  private static AuditAuthenticationEvidence authentication(ActorKind actorKind) {
    return actorKind == ActorKind.HUMAN
        ? AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN
        : AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
  }

  private static String authorizationFailure(AuthorizationDecision decision) {
    return decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
        ? "AUTHORIZATION_STORE_UNAVAILABLE"
        : "AUTHORIZATION_DENIED";
  }

  private static void requireFacilityScope(ExecutionContext context) {
    if (context == null
        || context.scope() == null
        || context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transfer patient-context participation requires a validated facility scope.");
    }
  }

  private static PlatformProblemException problem(PlatformErrorCode code, String detail) {
    return new PlatformProblemException(HttpStatus.SERVICE_UNAVAILABLE, code, detail);
  }
}
