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
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.ReceivingAssessment;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferDecision;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transfer-owned receiving assessment, Acceptance and Rejection commands. */
@Service
@Lazy
public final class TransferReceivingCommands {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String BUSY_DETAIL =
      "A matching Transfer command is still being processed; retry with the same idempotency key.";

  private final TransferStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public TransferReceivingCommands(
      @Lazy TransferStore store,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "Transfer store");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public ReceivingAssessmentCommandResult recordAssessment(
      ExecutionContext context,
      InternalId requestAttemptId,
      ReceivingAssessment.Kind kind,
      ReceivingAssessment.Outcome outcome,
      String reasonCode,
      Optional<String> evidenceReference,
      Instant occurredAt,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(kind, "assessment kind");
    Objects.requireNonNull(outcome, "assessment outcome");
    requireFacilityScope(context);
    requireAuthority(context, requestAttemptId, kind.authorityCode(), "TRANSFER_ASSESSMENT");
    CommandReceiptKey receiptKey =
        commandKey(
            context,
            "transfer.assessment."
                + (kind == ReceivingAssessment.Kind.CLINICAL_SUITABILITY
                    ? "clinical-suitability"
                    : "operational-readiness"),
            requestAttemptId,
            idempotencyKey);
    Optional<TransferStore.ReceiptClaim> existing = findReceipt(receiptKey, fingerprint);
    if (existing.isPresent()) {
      ReceivingAssessmentCommandResult replay =
          replayAssessment(requestAttemptId, kind, existing.orElseThrow());
      if (replay != null) {
        return replay;
      }
    }

    return execute(
        () -> {
          Instant now = clock.instant();
          store.configureBoundedReceiptWait();
          TransferStore.ReceiptClaim claim = store.claimReceipt(receiptKey, fingerprint, now);
          ReceivingAssessmentCommandResult replay = replayAssessment(requestAttemptId, kind, claim);
          if (replay != null) {
            return replay;
          }
          TransferRequestAttempt attempt = requiredAttempt(requestAttemptId);
          requireDestination(context, attempt);
          requirePending(attempt, expectedVersion, now);
          long resultVersion = expectedVersion + 1;
          if (!store.advancePendingRequest(requestAttemptId, expectedVersion, "PENDING", now)) {
            throw stateConflict();
          }
          ReceivingAssessment assessment =
              new ReceivingAssessment(
                  InternalId.generate(),
                  requestAttemptId,
                  kind,
                  outcome,
                  reasonCode,
                  evidenceReference,
                  context.actor(),
                  kind.authorityCode(),
                  context.scope().organization().orElseThrow(),
                  context.scope().facility().orElseThrow(),
                  occurredAt,
                  now,
                  resultVersion);
          store.insertAssessment(assessment);
          auditMutation(context, requestAttemptId, "TRANSFER_" + kind.name() + "_RECORD");
          store.completeReceipt(
              receiptKey, HttpStatus.CREATED.value(), attempt.journeyId(), resultVersion, now);
          return new ReceivingAssessmentCommandResult(assessment, false);
        });
  }

  public TransferDecisionCommandResult accept(
      ExecutionContext context,
      InternalId requestAttemptId,
      Instant occurredAt,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    return recordDecision(
        context,
        requestAttemptId,
        TransferDecision.Kind.ACCEPTED,
        Optional.empty(),
        occurredAt,
        expectedVersion,
        idempotencyKey,
        fingerprint);
  }

  public TransferDecisionCommandResult reject(
      ExecutionContext context,
      InternalId requestAttemptId,
      String reasonCode,
      Instant occurredAt,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    return recordDecision(
        context,
        requestAttemptId,
        TransferDecision.Kind.REJECTED,
        Optional.of(reasonCode),
        occurredAt,
        expectedVersion,
        idempotencyKey,
        fingerprint);
  }

  private TransferDecisionCommandResult recordDecision(
      ExecutionContext context,
      InternalId requestAttemptId,
      TransferDecision.Kind kind,
      Optional<String> reasonCode,
      Instant occurredAt,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireFacilityScope(context);
    requireAuthority(
        context,
        requestAttemptId,
        TransferAuthorities.acceptance().code(),
        kind == TransferDecision.Kind.ACCEPTED ? "TRANSFER_ACCEPT" : "TRANSFER_REJECT");
    String operation =
        kind == TransferDecision.Kind.ACCEPTED
            ? "transfer.request.accept"
            : "transfer.request.reject";
    CommandReceiptKey receiptKey = commandKey(context, operation, requestAttemptId, idempotencyKey);
    Optional<TransferStore.ReceiptClaim> existing = findReceipt(receiptKey, fingerprint);
    if (existing.isPresent()) {
      TransferDecisionCommandResult replay =
          replayDecision(requestAttemptId, existing.orElseThrow());
      if (replay != null) {
        return replay;
      }
    }

    return execute(
        () -> {
          Instant now = clock.instant();
          store.configureBoundedReceiptWait();
          TransferStore.ReceiptClaim claim = store.claimReceipt(receiptKey, fingerprint, now);
          TransferDecisionCommandResult replay = replayDecision(requestAttemptId, claim);
          if (replay != null) {
            return replay;
          }
          TransferRequestAttempt attempt = requiredAttempt(requestAttemptId);
          requireDestination(context, attempt);
          requirePending(attempt, expectedVersion, now);
          Optional<CoordinatedTransfer> transfer = Optional.empty();
          Optional<InternalId> transferId = Optional.empty();
          if (kind == TransferDecision.Kind.ACCEPTED) {
            requireSupportingAssessment(
                requestAttemptId, ReceivingAssessment.Kind.CLINICAL_SUITABILITY);
            requireSupportingAssessment(
                requestAttemptId, ReceivingAssessment.Kind.OPERATIONAL_READINESS);
            CoordinatedTransfer created =
                new CoordinatedTransfer(
                    InternalId.generate(),
                    attempt.journeyId(),
                    requestAttemptId,
                    CoordinatedTransfer.Status.AWAITING_RESERVATION,
                    now,
                    1);
            transfer = Optional.of(created);
            transferId = Optional.of(created.id());
          }
          long resultVersion = expectedVersion + 1;
          if (!store.advancePendingRequest(
              requestAttemptId,
              expectedVersion,
              kind == TransferDecision.Kind.ACCEPTED ? "ACCEPTED" : "REJECTED",
              now)) {
            throw stateConflict();
          }
          TransferDecision decision =
              new TransferDecision(
                  InternalId.generate(),
                  requestAttemptId,
                  kind,
                  reasonCode,
                  transferId,
                  context.actor(),
                  TransferAuthorities.acceptance().code(),
                  context.scope().organization().orElseThrow(),
                  context.scope().facility().orElseThrow(),
                  occurredAt,
                  now,
                  resultVersion);
          store.insertDecisionAndOptionalTransfer(decision, transfer);
          auditMutation(
              context,
              requestAttemptId,
              kind == TransferDecision.Kind.ACCEPTED ? "TRANSFER_ACCEPT" : "TRANSFER_REJECT");
          store.completeReceipt(
              receiptKey, HttpStatus.CREATED.value(), attempt.journeyId(), resultVersion, now);
          return new TransferDecisionCommandResult(decision, transfer, false);
        });
  }

  private void requireSupportingAssessment(
      InternalId requestAttemptId, ReceivingAssessment.Kind kind) {
    ReceivingAssessment assessment =
        store
            .findLatestAssessment(requestAttemptId, kind)
            .orElseThrow(TransferReceivingCommands::assessmentRequired);
    if (assessment.outcome() != ReceivingAssessment.Outcome.SUPPORTS) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_ASSESSMENT_NOT_POSITIVE,
          "Acceptance requires positive latest receiving assessments.");
    }
  }

  private Optional<TransferStore.ReceiptClaim> findReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint) {
    try {
      return store.findReceipt(key, fingerprint);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private ReceivingAssessmentCommandResult replayAssessment(
      InternalId requestAttemptId,
      ReceivingAssessment.Kind kind,
      TransferStore.ReceiptClaim claim) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED -> throw keyReused();
      case IN_PROGRESS -> throw commandBusy();
      case REPLAY -> {
        long version = claim.resultVersion().orElseThrow();
        ReceivingAssessment assessment =
            store
                .findAssessmentByVersion(requestAttemptId, kind, version)
                .orElseThrow(TransferReceivingCommands::unavailable);
        yield new ReceivingAssessmentCommandResult(assessment, true);
      }
    };
  }

  private TransferDecisionCommandResult replayDecision(
      InternalId requestAttemptId, TransferStore.ReceiptClaim claim) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED -> throw keyReused();
      case IN_PROGRESS -> throw commandBusy();
      case REPLAY -> {
        long version = claim.resultVersion().orElseThrow();
        TransferStore.DecisionResult result =
            store
                .findDecisionByVersion(requestAttemptId, version)
                .orElseThrow(TransferReceivingCommands::unavailable);
        yield new TransferDecisionCommandResult(result.decision(), result.transfer(), true);
      }
    };
  }

  private TransferRequestAttempt requiredAttempt(InternalId requestAttemptId) {
    return store
        .findRequestAttempt(requestAttemptId)
        .orElseThrow(TransferReceivingCommands::notFound);
  }

  private static void requireFacilityScope(ExecutionContext context) {
    if (context == null
        || context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()
        || !DIRECTORY_ISSUER.equals(context.scope().organization().orElseThrow().issuer().value())
        || !DIRECTORY_ISSUER.equals(context.scope().facility().orElseThrow().issuer().value())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Receiving commands require a validated Directory Facility scope.");
    }
    InternalId.parse(context.scope().organization().orElseThrow().value());
    InternalId.parse(context.scope().facility().orElseThrow().value());
  }

  private void requireAuthority(
      ExecutionContext context, InternalId attemptId, String authorityCode, String action) {
    AuthorizationDecision decision = authorization.getObject().evaluate(context, authorityCode);
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElseThrow()
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    recordDenial(
        context,
        attemptId,
        action + "_DENIED",
        unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED");
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer authorization is temporarily unavailable."
            : "The actor is not authorized for this receiving function at this Facility.");
  }

  private void requireDestination(ExecutionContext context, TransferRequestAttempt attempt) {
    if (!attempt.destinationFacility().equals(context.scope().facility().orElseThrow())) {
      recordDenial(context, attempt.id(), "TRANSFER_SCOPE_MISMATCH", "TRANSFER_SCOPE_MISMATCH");
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.TRANSFER_SCOPE_MISMATCH,
          "The request attempt is outside the authorized receiving Facility scope.");
    }
  }

  private static void requirePending(
      TransferRequestAttempt attempt, long expectedVersion, Instant now) {
    if (attempt.status() != TransferRequestAttempt.Status.PENDING) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_REQUEST_NOT_PENDING,
          "The request attempt no longer accepts receiving decisions.");
    }
    if (!attempt.expiresAt().isAfter(now)) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_REQUEST_EXPIRED,
          "The request attempt has expired and cannot accept a receiving decision.");
    }
    if (attempt.version() != expectedVersion) {
      throw stateConflict();
    }
  }

  private void auditMutation(ExecutionContext context, InternalId attemptId, String action) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              new AuditEvidence(
                  AuditActionKind.PROTECTED_MUTATION,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  action,
                  "TransferRequestAttempt",
                  Optional.of(attemptId),
                  clock.instant(),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The receiving decision and its required audit evidence could not be committed.");
    }
  }

  private void recordDenial(
      ExecutionContext context, InternalId attemptId, String action, String reasonCode) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  action,
                  "TransferRequestAttempt",
                  Optional.of(attemptId),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of(reasonCode),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId(),
                  Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private static AuditTargetContext targetContext(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
  }

  private static AuditAuthenticationEvidence authenticationEvidence(
      AuthenticationMechanism mechanism) {
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private CommandReceiptKey commandKey(
      ExecutionContext context, String operation, InternalId attemptId, String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context, operation, "transfer-request-attempts/" + attemptId, idempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "A valid Idempotency-Key is required for this Transfer command.");
    }
  }

  private <T> T execute(java.util.function.Supplier<T> operation) {
    try {
      return transaction.execute(status -> operation.get());
    } catch (CannotAcquireLockException busy) {
      throw commandBusy();
    } catch (DuplicateKeyException conflict) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_ACCEPTANCE_CONFLICT,
          "A receiving decision already exists for this request attempt.");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.TRANSFER_REQUEST_NOT_FOUND,
        "The Transfer request attempt was not found.");
  }

  private static PlatformProblemException assessmentRequired() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_ASSESSMENT_REQUIRED,
        "Acceptance requires both clinical suitability and operational readiness assessments.");
  }

  private static PlatformProblemException stateConflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_STATE_CONFLICT,
        "The request attempt changed; reload it and retry with its current version.");
  }

  private static PlatformProblemException keyReused() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
        "The Idempotency-Key was already used for a different Transfer command.");
  }

  private static PlatformProblemException commandBusy() {
    return problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "The Transfer receiving decision could not be committed safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
