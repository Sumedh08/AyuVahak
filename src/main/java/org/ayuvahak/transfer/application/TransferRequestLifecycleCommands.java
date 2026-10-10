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
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner commands for sender withdrawal and scheduled request expiry. */
@Service
@Lazy
public final class TransferRequestLifecycleCommands {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String BUSY_DETAIL =
      "A matching Transfer command is still being processed; retry with the same idempotency key.";

  private final TransferStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public TransferRequestLifecycleCommands(
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

  public TransferRequestLifecycleCommandResult withdraw(
      ExecutionContext context,
      InternalId requestAttemptId,
      String reasonCode,
      Instant occurredAt,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireOriginScope(context);
    requireAuthority(context, requestAttemptId, TransferAuthorities.request().code(), "WITHDRAW");
    CommandReceiptKey receiptKey =
        commandKey(context, "transfer.request.withdraw", requestAttemptId, idempotencyKey);
    return execute(
        () -> {
          Instant now = clock.instant();
          store.configureBoundedReceiptWait();
          TransferStore.ReceiptClaim claim = store.claimReceipt(receiptKey, fingerprint, now);
          TransferRequestLifecycleCommandResult replay = replay(requestAttemptId, claim);
          if (replay != null) {
            return replay;
          }
          TransferRequestAttempt attempt = requiredAttempt(requestAttemptId);
          TransferJourney journey = requiredJourney(attempt);
          requireOriginTarget(context, journey, requestAttemptId);
          requirePending(attempt, expectedVersion, now);
          long resultVersion = expectedVersion + 1;
          if (!store.advancePendingRequest(requestAttemptId, expectedVersion, "WITHDRAWN", now)) {
            throw stateConflict();
          }
          auditMutation(context, requestAttemptId, "TRANSFER_REQUEST_WITHDRAW", reasonCode, now);
          store.completeReceipt(
              receiptKey, HttpStatus.CREATED.value(), journey.id(), resultVersion, now);
          return new TransferRequestLifecycleCommandResult(
              attemptWithStatus(attempt, TransferRequestAttempt.Status.WITHDRAWN, resultVersion),
              false);
        });
  }

  /** Called only by Transfer's scheduled owner worker; no HTTP expiry command is exposed. */
  public TransferRequestLifecycleCommandResult expire(
      ExecutionContext context,
      InternalId requestAttemptId,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireExpiryWorkerContext(context);
    requireAuthority(
        context, requestAttemptId, TransferAuthorities.requestExpiry().code(), "EXPIRE");
    CommandReceiptKey receiptKey =
        commandKey(context, "transfer.request.expire", requestAttemptId, idempotencyKey);
    return execute(
        () -> {
          Instant now = clock.instant();
          store.configureBoundedReceiptWait();
          TransferStore.ReceiptClaim claim = store.claimReceipt(receiptKey, fingerprint, now);
          TransferRequestLifecycleCommandResult replay = replay(requestAttemptId, claim);
          if (replay != null) {
            return replay;
          }
          TransferRequestAttempt attempt = requiredAttempt(requestAttemptId);
          TransferJourney journey = requiredJourney(attempt);
          requireOriginOrganization(context, journey, requestAttemptId);
          if (attempt.status() != TransferRequestAttempt.Status.PENDING) {
            throw notPending();
          }
          if (attempt.version() != expectedVersion) {
            throw stateConflict();
          }
          if (attempt.expiresAt().isAfter(now)) {
            throw problem(
                HttpStatus.CONFLICT,
                PlatformErrorCode.TRANSFER_STATE_CONFLICT,
                "The Transfer request deadline has not elapsed.");
          }
          long resultVersion = expectedVersion + 1;
          if (!store.expirePendingRequest(requestAttemptId, expectedVersion, now)) {
            throw stateConflict();
          }
          auditMutation(
              context, requestAttemptId, "TRANSFER_REQUEST_EXPIRE", "DEADLINE_ELAPSED", now);
          store.completeReceipt(
              receiptKey, HttpStatus.CREATED.value(), journey.id(), resultVersion, now);
          return new TransferRequestLifecycleCommandResult(
              attemptWithStatus(attempt, TransferRequestAttempt.Status.EXPIRED, resultVersion),
              false);
        });
  }

  private TransferRequestLifecycleCommandResult replay(
      InternalId requestAttemptId, TransferStore.ReceiptClaim claim) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The Idempotency-Key was already used for a different Transfer command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
      case REPLAY -> {
        TransferRequestAttempt attempt = requiredAttempt(requestAttemptId);
        if (attempt.version() < claim.resultVersion().orElseThrow()) {
          throw unavailable();
        }
        yield new TransferRequestLifecycleCommandResult(attempt, true);
      }
    };
  }

  private TransferRequestAttempt requiredAttempt(InternalId id) {
    return store.findRequestAttempt(id).orElseThrow(TransferRequestLifecycleCommands::notFound);
  }

  private TransferJourney requiredJourney(TransferRequestAttempt attempt) {
    return store
        .findJourney(attempt.journeyId())
        .orElseThrow(TransferRequestLifecycleCommands::unavailable);
  }

  private static void requireOriginScope(ExecutionContext context) {
    if (context == null
        || context.scope().kind() == ExecutionScope.Kind.PLATFORM
        || context.scope().organization().isEmpty()
        || !DIRECTORY_ISSUER.equals(context.scope().organization().orElseThrow().issuer().value())
        || context
            .scope()
            .facility()
            .filter(facility -> !DIRECTORY_ISSUER.equals(facility.issuer().value()))
            .isPresent()) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Withdrawal requires a validated Directory origin scope.");
    }
    InternalId.parse(context.scope().organization().orElseThrow().value());
    context.scope().facility().ifPresent(facility -> InternalId.parse(facility.value()));
  }

  private static void requireExpiryWorkerContext(ExecutionContext context) {
    if (context == null
        || context.actor().kind() != ActorKind.SERVICE
        || context.authenticationMechanism() != AuthenticationMechanism.CONFIGURED_SERVICE_IDENTITY
        || context.scope().kind() != ExecutionScope.Kind.ORGANIZATION
        || context.scope().organization().isEmpty()
        || context.scope().facility().isPresent()
        || !DIRECTORY_ISSUER.equals(
            context.scope().organization().orElseThrow().issuer().value())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Request expiry requires the configured Transfer worker at organization scope.");
    }
    InternalId.parse(context.scope().organization().orElseThrow().value());
  }

  private void requireOriginTarget(
      ExecutionContext context, TransferJourney journey, InternalId attemptId) {
    boolean organizationMatches =
        journey.originOrganization().equals(context.scope().organization().orElseThrow());
    boolean facilityMatches =
        context.scope().facility().isEmpty()
            || journey.originFacility().equals(context.scope().facility());
    if (!organizationMatches || !facilityMatches) {
      recordDenial(
          context, attemptId, "TRANSFER_WITHDRAW_SCOPE_MISMATCH", "TRANSFER_SCOPE_MISMATCH");
      throw scopeMismatch();
    }
  }

  private void requireOriginOrganization(
      ExecutionContext context, TransferJourney journey, InternalId attemptId) {
    if (!journey.originOrganization().equals(context.scope().organization().orElseThrow())) {
      recordDenial(context, attemptId, "TRANSFER_EXPIRE_SCOPE_MISMATCH", "TRANSFER_SCOPE_MISMATCH");
      throw scopeMismatch();
    }
  }

  private void requireAuthority(
      ExecutionContext context, InternalId attemptId, String authority, String action) {
    AuthorizationDecision decision = authorization.getObject().evaluate(context, authority);
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElseThrow()
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    recordDenial(
        context,
        attemptId,
        "TRANSFER_REQUEST_" + action + "_DENIED",
        unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED");
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer authorization is temporarily unavailable."
            : "The actor is not authorized for this Transfer request operation.");
  }

  private void auditMutation(
      ExecutionContext context, InternalId attemptId, String action, String reason, Instant now) {
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
                  now,
                  AuditOutcome.SUCCEEDED,
                  Optional.of(reason),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Transfer request transition and required audit evidence could not be committed.");
    }
  }

  private void recordDenial(
      ExecutionContext context, InternalId attemptId, String action, String reason) {
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
                  Optional.of(reason),
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
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private void requirePending(TransferRequestAttempt attempt, long expectedVersion, Instant now) {
    if (attempt.status() != TransferRequestAttempt.Status.PENDING) {
      throw notPending();
    }
    if (attempt.version() != expectedVersion) {
      throw stateConflict();
    }
    if (!attempt.expiresAt().isAfter(now)) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_REQUEST_EXPIRED,
          "The request deadline elapsed; its expiry transition is being processed.");
    }
  }

  private static TransferRequestAttempt attemptWithStatus(
      TransferRequestAttempt attempt, TransferRequestAttempt.Status status, long version) {
    return new TransferRequestAttempt(
        attempt.id(),
        attempt.journeyId(),
        attempt.attemptNumber(),
        attempt.referralId(),
        attempt.referralRevision(),
        attempt.recommendationId(),
        attempt.destinationFacility(),
        status,
        attempt.requestedBy(),
        attempt.requestedAt(),
        attempt.expiresAt(),
        version);
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

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.TRANSFER_REQUEST_NOT_FOUND,
        "The Transfer request attempt was not found.");
  }

  private static PlatformProblemException notPending() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_REQUEST_NOT_PENDING,
        "The Transfer request attempt already has a terminal outcome.");
  }

  private static PlatformProblemException stateConflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_STATE_CONFLICT,
        "The request attempt changed; reload it and retry with its current version.");
  }

  private static PlatformProblemException scopeMismatch() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.TRANSFER_SCOPE_MISMATCH,
        "The request attempt is outside the authorized origin scope.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "The Transfer request lifecycle could not be committed safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
