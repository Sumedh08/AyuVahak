package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
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
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.DestinationChangeAssessment;
import org.ayuvahak.transfer.domain.ReceivingAssessment;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transfer-owned receiving assessments and decisions for a proposed reroute destination. */
@Service
public final class TransferDestinationChangeReceivingCommands {

  private final TransferStore transfers;
  private final TransferDestinationChangeCoordinator coordinator;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public TransferDestinationChangeReceivingCommands(
      TransferStore transfers,
      TransferDestinationChangeCoordinator coordinator,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.transfers = Objects.requireNonNull(transfers, "Transfer store");
    this.coordinator = Objects.requireNonNull(coordinator, "destination-change coordinator");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public AssessmentResult recordAssessment(
      ExecutionContext context,
      InternalId transferId,
      InternalId operationId,
      ReceivingAssessment.Kind kind,
      ReceivingAssessment.Outcome outcome,
      String reasonCode,
      Optional<String> evidenceReference,
      Instant occurredAt,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireFacilityContext(context);
    requireAuthority(context, operationId, kind.authorityCode());
    CommandReceiptKey receipt =
        receiptKey(
            context,
            transferId,
            operationId,
            "assessment-" + kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
            idempotencyKey);
    try {
      AssessmentResult result =
          Objects.requireNonNull(
              transaction.execute(
                  ignored -> {
                    Instant now = clock.instant();
                    transfers.configureBoundedReceiptWait();
                    TransferStore.DestinationChangeReceiptClaim claim =
                        transfers.claimDestinationChangeReceipt(
                            receipt, fingerprint, operationId, now);
                    if (claim.state() == TransferStore.ReceiptState.KEY_REUSED) {
                      throw conflict(
                          PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                          "The Idempotency-Key was already used for a different destination-change command.");
                    }
                    if (claim.state() == TransferStore.ReceiptState.IN_PROGRESS) {
                      throw conflict(
                          PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
                          "The destination-change assessment is still processing; retry with the same key.");
                    }
                    TransferDestinationChangeOperation operation =
                        transfers
                            .lockDestinationChange(operationId)
                            .filter(found -> found.transferId().equals(transferId))
                            .orElseThrow(TransferDestinationChangeReceivingCommands::notFound);
                    requireProposedScope(context, operation);
                    if (claim.state() == TransferStore.ReceiptState.REPLAY) {
                      DestinationChangeAssessment existing =
                          transfers
                              .findDestinationChangeAssessment(operationId, kind)
                              .orElseThrow(TransferDestinationChangeReceivingCommands::unavailable);
                      return new AssessmentResult(existing, true);
                    }
                    if (operation.status()
                            != TransferDestinationChangeOperation.Status.AWAITING_RECEIVER_DECISIONS
                        || operation.version() != expectedVersion) {
                      throw conflict(
                          PlatformErrorCode.TRANSFER_STATE_CONFLICT,
                          "The destination-change proposal no longer accepts receiving assessments.");
                    }
                    if (transfers.findDestinationChangeAssessment(operationId, kind).isPresent()) {
                      throw conflict(
                          PlatformErrorCode.TRANSFER_STATE_CONFLICT,
                          "A receiving assessment of this kind is already recorded.");
                    }
                    DestinationChangeAssessment assessment =
                        new DestinationChangeAssessment(
                            InternalId.generate(),
                            operationId,
                            kind,
                            outcome,
                            reasonCode,
                            evidenceReference,
                            context.actor(),
                            context.scope().organization().orElseThrow(),
                            context.scope().facility().orElseThrow(),
                            occurredAt,
                            now);
                    transfers.insertDestinationChangeAssessment(assessment);
                    auditMutation(
                        context, operationId, "TRANSFER_DESTINATION_CHANGE_" + kind.name());
                    transfers.completeDestinationChangeReceipt(
                        receipt, fingerprint, operationId, now);
                    return new AssessmentResult(assessment, false);
                  }),
              "destination-change assessment result");
      return result;
    } catch (CannotAcquireLockException busy) {
      throw conflict(
          PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
          "The destination-change assessment is still processing; retry with the same key.");
    } catch (DuplicateKeyException duplicate) {
      throw conflict(
          PlatformErrorCode.TRANSFER_STATE_CONFLICT,
          "A receiving assessment of this kind is already recorded.");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  public DecisionResult accept(
      ExecutionContext context,
      InternalId transferId,
      InternalId operationId,
      Instant occurredAt,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    return decide(
        context,
        transferId,
        operationId,
        "ACCEPTED",
        Optional.empty(),
        occurredAt,
        expectedVersion,
        idempotencyKey,
        fingerprint);
  }

  public DecisionResult reject(
      ExecutionContext context,
      InternalId transferId,
      InternalId operationId,
      String reasonCode,
      Instant occurredAt,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    return decide(
        context,
        transferId,
        operationId,
        "REJECTED",
        Optional.of(reasonCode),
        occurredAt,
        expectedVersion,
        idempotencyKey,
        fingerprint);
  }

  private DecisionResult decide(
      ExecutionContext context,
      InternalId transferId,
      InternalId operationId,
      String decision,
      Optional<String> reasonCode,
      Instant occurredAt,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireFacilityContext(context);
    requireAuthority(context, operationId, TransferAuthorities.acceptance().code());
    CommandReceiptKey receipt =
        receiptKey(
            context,
            transferId,
            operationId,
            "decision-" + decision.toLowerCase(java.util.Locale.ROOT),
            idempotencyKey);
    try {
      DecisionResult result =
          Objects.requireNonNull(
              transaction.execute(
                  ignored -> {
                    Instant now = clock.instant();
                    transfers.configureBoundedReceiptWait();
                    TransferStore.DestinationChangeReceiptClaim claim =
                        transfers.claimDestinationChangeReceipt(
                            receipt, fingerprint, operationId, now);
                    if (claim.state() == TransferStore.ReceiptState.KEY_REUSED) {
                      throw conflict(
                          PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                          "The Idempotency-Key was already used for a different destination-change command.");
                    }
                    if (claim.state() == TransferStore.ReceiptState.IN_PROGRESS) {
                      throw conflict(
                          PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
                          "The destination-change decision is still processing; retry with the same key.");
                    }
                    TransferDestinationChangeOperation current =
                        transfers
                            .lockDestinationChange(operationId)
                            .filter(found -> found.transferId().equals(transferId))
                            .orElseThrow(TransferDestinationChangeReceivingCommands::notFound);
                    requireProposedScope(context, current);
                    if (claim.state() == TransferStore.ReceiptState.REPLAY) {
                      return new DecisionResult(current, true);
                    }
                    if (current.status()
                            != TransferDestinationChangeOperation.Status.AWAITING_RECEIVER_DECISIONS
                        || current.version() != expectedVersion) {
                      throw conflict(
                          PlatformErrorCode.TRANSFER_STATE_CONFLICT,
                          "The destination-change proposal changed; reload its current version.");
                    }
                    if (decision.equals("ACCEPTED")) {
                      requireSupportingAssessment(
                          operationId, ReceivingAssessment.Kind.CLINICAL_SUITABILITY);
                      requireSupportingAssessment(
                          operationId, ReceivingAssessment.Kind.OPERATIONAL_READINESS);
                    }
                    if (!transfers.insertDestinationChangeDecision(
                        operationId,
                        decision,
                        reasonCode,
                        context.actor(),
                        context.scope().organization().orElseThrow(),
                        context.scope().facility().orElseThrow(),
                        occurredAt,
                        now)) {
                      throw conflict(
                          PlatformErrorCode.TRANSFER_STATE_CONFLICT,
                          "A receiving decision is already recorded.");
                    }
                    TransferDestinationChangeOperation.Status nextStatus =
                        decision.equals("ACCEPTED")
                            ? TransferDestinationChangeOperation.Status.CAPACITY_RESERVATION_PENDING
                            : TransferDestinationChangeOperation.Status.RESOLVED;
                    TransferDestinationChangeOperation updated =
                        copy(
                            current,
                            nextStatus,
                            current.capacityReservationOutcome(),
                            Optional.empty(),
                            now);
                    if (!transfers.updateDestinationChange(updated, current.version())) {
                      throw conflict(
                          PlatformErrorCode.TRANSFER_STATE_CONFLICT,
                          "The destination-change proposal changed concurrently.");
                    }
                    auditMutation(
                        context,
                        operationId,
                        decision.equals("ACCEPTED")
                            ? "TRANSFER_DESTINATION_CHANGE_ACCEPTED"
                            : "TRANSFER_DESTINATION_CHANGE_REJECTED");
                    transfers.completeDestinationChangeReceipt(
                        receipt, fingerprint, operationId, now);
                    return new DecisionResult(updated, false);
                  }),
              "destination-change decision result");
      if (decision.equals("ACCEPTED")) {
        return new DecisionResult(
            coordinator.resumeAfterAcceptance(result.operation()), result.replayed());
      }
      return result;
    } catch (CannotAcquireLockException busy) {
      throw conflict(
          PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
          "The destination-change decision is still processing; retry with the same key.");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private void requireSupportingAssessment(InternalId operationId, ReceivingAssessment.Kind kind) {
    DestinationChangeAssessment assessment =
        transfers
            .findDestinationChangeAssessment(operationId, kind)
            .orElseThrow(
                () ->
                    conflict(
                        PlatformErrorCode.TRANSFER_ASSESSMENT_REQUIRED,
                        "Acceptance requires separate positive clinical and operational assessments."));
    if (assessment.outcome() != ReceivingAssessment.Outcome.SUPPORTS) {
      throw conflict(
          PlatformErrorCode.TRANSFER_ASSESSMENT_REQUIRED,
          "Acceptance requires separate positive clinical and operational assessments.");
    }
  }

  private void requireAuthority(ExecutionContext context, InternalId operationId, String code) {
    AuthorizationDecision decision = authorization.evaluate(context, code);
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_DESTINATION_CHANGE_RECEIVING_DENIED",
              "TransferDestinationChange",
              Optional.of(operationId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
              authentication(context),
              AuditAuthorizationOutcome.DENIED,
              context.correlationId(),
              Optional.of(target(context.scope()))));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied because its required security audit is unavailable.");
    }
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        "The actor is not authorized for this receiving function at the proposed Facility.");
  }

  private void auditMutation(ExecutionContext context, InternalId operationId, String action) {
    try {
      audit.recordMutation(
          new AuditEvidence(
              AuditActionKind.PROTECTED_MUTATION,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              action,
              "TransferDestinationChange",
              Optional.of(operationId),
              clock.instant(),
              AuditOutcome.SUCCEEDED,
              Optional.empty(),
              authentication(context),
              AuditAuthorizationOutcome.ALLOWED,
              context.correlationId(),
              Optional.of(target(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The receiving decision and its required audit evidence could not be committed.");
    }
  }

  private static void requireFacilityContext(ExecutionContext context) {
    if (context == null
        || context.actor().kind() != ActorKind.HUMAN
        || context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Receiving decisions require a human actor in validated Facility scope.");
    }
  }

  private static void requireProposedScope(
      ExecutionContext context, TransferDestinationChangeOperation operation) {
    if (!operation.proposedOrganization().equals(context.scope().organization().orElseThrow())
        || !operation.proposedFacility().equals(context.scope().facility().orElseThrow())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.TRANSFER_SCOPE_MISMATCH,
          "The destination-change proposal is outside the authorized receiving Facility scope.");
    }
  }

  private static CommandReceiptKey receiptKey(
      ExecutionContext context,
      InternalId transferId,
      InternalId operationId,
      String action,
      String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context,
          "transfer.destination-change." + action,
          "transfer/" + transferId + "/destination-changes/" + operationId,
          idempotencyKey);
    } catch (IllegalArgumentException malformed) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "A valid Idempotency-Key is required for this receiving decision.");
    }
  }

  private static TransferDestinationChangeOperation copy(
      TransferDestinationChangeOperation current,
      TransferDestinationChangeOperation.Status status,
      TransferDestinationChangeOperation.CapacityOutcome capacityOutcome,
      Optional<TransferDestinationChangeOperation.ProviderOutcome> providerOutcome,
      Instant now) {
    return new TransferDestinationChangeOperation(
        current.id(),
        current.transferId(),
        current.transportRequestId(),
        current.oldOrganization(),
        current.oldFacility(),
        current.proposedOrganization(),
        current.proposedFacility(),
        current.recommendationId(),
        current.referralRevision(),
        current.reasonCode(),
        current.reservationPlan(),
        status,
        current.capacityReservationOperationId(),
        capacityOutcome,
        current.oldReservationOperationId(),
        current.oldHoldReleaseOperationId(),
        current.proposedHoldReleaseOperationId(),
        current.transportOperationId(),
        providerOutcome,
        current.initiatedBy(),
        current.originOrganization(),
        current.originFacility(),
        current.correlationId(),
        current.version() + 1,
        current.createdAt(),
        now);
  }

  private static AuditAuthenticationEvidence authentication(ExecutionContext context) {
    return switch (context.authenticationMechanism()) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static AuditTargetContext target(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.TRANSFER_STATE_CONFLICT,
        "The destination-change proposal was not found.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "Transfer could not record the receiving decision safely.");
  }

  private static PlatformProblemException conflict(PlatformErrorCode code, String detail) {
    return problem(HttpStatus.CONFLICT, code, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  public record AssessmentResult(DestinationChangeAssessment assessment, boolean replayed) {}

  public record DecisionResult(TransferDestinationChangeOperation operation, boolean replayed) {}
}
