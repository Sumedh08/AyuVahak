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
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferDecision;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Transfer-owned, origin-scoped status query used before Referral changes a submitted revision. */
@Service
public final class TransferAmendmentStatusQuery {

  private final TransferStore transfers;
  private final AuthorizationService authorization;
  private final AuditWriter auditWriter;
  private final TransactionTemplate ownerRead;
  private final Clock clock;

  public TransferAmendmentStatusQuery(
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

  /** Returns true only when Transfer has a request or Transfer still in active coordination. */
  public boolean hasActiveCoordination(ExecutionContext context, InternalId referralId) {
    requireOriginScope(context);
    Objects.requireNonNull(referralId, "Referral id");
    try {
      return Boolean.TRUE.equals(ownerRead.execute(status -> read(context, referralId)));
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private boolean read(ExecutionContext context, InternalId referralId) {
    requireAuthority(context, referralId);
    Optional<org.ayuvahak.transfer.domain.TransferJourney> journeyResult =
        transfers.findJourneyByReferral(referralId);
    boolean active =
        journeyResult.map(journey -> activeJourney(context, referralId, journey)).orElse(false);
    auditRead(context, referralId, active);
    return active;
  }

  private boolean activeJourney(
      ExecutionContext context,
      InternalId referralId,
      org.ayuvahak.transfer.domain.TransferJourney journey) {
    if (!journey.referralId().equals(referralId)
        || !context.scope().organization().equals(Optional.of(journey.originOrganization()))
        || !context.scope().facility().equals(journey.originFacility())) {
      auditScopeMismatch(context, referralId);
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSFER_UNAVAILABLE,
          "Transfer could not verify the Referral's origin coordination state.");
    }
    if (transfers.findJourneyClosure(journey.id()).isPresent()) {
      return false;
    }
    TransferRequestAttempt latest =
        transfers
            .findLatestRequest(journey.id())
            .orElseThrow(TransferAmendmentStatusQuery::unavailable);
    return switch (latest.status()) {
      case PENDING -> true;
      case REJECTED, EXPIRED, WITHDRAWN -> false;
      case ACCEPTED -> acceptedTransferIsActive(latest);
    };
  }

  private boolean acceptedTransferIsActive(TransferRequestAttempt attempt) {
    TransferStore.DecisionResult decision =
        transfers
            .findDecisionByVersion(attempt.id(), attempt.version())
            .orElseThrow(TransferAmendmentStatusQuery::unavailable);
    if (decision.decision().kind() != TransferDecision.Kind.ACCEPTED
        || decision.transfer().isEmpty()) {
      throw unavailable();
    }
    CoordinatedTransfer.Status status = decision.transfer().orElseThrow().status();
    return status != CoordinatedTransfer.Status.COMPLETED
        && status != CoordinatedTransfer.Status.CANCELLED;
  }

  private void requireAuthority(ExecutionContext context, InternalId referralId) {
    AuthorizationDecision decision =
        authorization.evaluate(context, TransferAuthorities.referralAmendmentCheck().code());
    if (decision.isAllowed()) {
      return;
    }
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_REFERRAL_AMENDMENT_CHECK_DENIED",
              "Referral",
              Optional.of(referralId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(
                  decision.denialReason().orElseThrow()
                          == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
                      ? "AUTHORIZATION_STORE_UNAVAILABLE"
                      : "AUTHORIZATION_DENIED"),
              AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
              AuditAuthorizationOutcome.DENIED,
              context.correlationId(),
              Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transfer could not record the denied amendment-status read safely.");
    }
    throw new PlatformProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
        "Transfer could not authorize the Referral amendment-status read.");
  }

  private void auditScopeMismatch(ExecutionContext context, InternalId referralId) {
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_REFERRAL_AMENDMENT_SCOPE_MISMATCH",
              "Referral",
              Optional.of(referralId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of("ORIGIN_SCOPE_MISMATCH"),
              AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
              AuditAuthorizationOutcome.DENIED,
              context.correlationId(),
              Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transfer could not record the denied amendment-status read safely.");
    }
  }

  private void auditRead(ExecutionContext context, InternalId referralId, boolean active) {
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SENSITIVE_READ,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_REFERRAL_AMENDMENT_STATUS_READ",
              "Referral",
              Optional.of(referralId),
              clock.instant(),
              AuditOutcome.SUCCEEDED,
              Optional.of(active ? "ACTIVE_COORDINATION" : "NO_ACTIVE_COORDINATION"),
              AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
              AuditAuthorizationOutcome.ALLOWED,
              context.correlationId(),
              Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transfer withheld amendment status because its read audit is unavailable.");
    }
  }

  private static void requireOriginScope(ExecutionContext context) {
    if (context == null
        || (context.scope().kind() != ExecutionScope.Kind.ORGANIZATION
            && context.scope().kind() != ExecutionScope.Kind.FACILITY)
        || context.scope().organization().isEmpty()) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transfer amendment-status reads require a validated origin scope.");
    }
  }

  private static AuditTargetContext targetContext(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "Transfer could not establish the Referral's current coordination state.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
