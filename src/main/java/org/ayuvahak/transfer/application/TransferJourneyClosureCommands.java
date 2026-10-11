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
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferJourneyClosure;
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
import tools.jackson.databind.ObjectMapper;

/** Commits an authorized terminal Journey closure and its unfulfilled Referral event together. */
@Service
@Lazy
public final class TransferJourneyClosureCommands {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String BUSY_DETAIL =
      "A matching Transfer command is still being processed; retry with the same idempotency key.";

  private final TransferStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final OutboxWriter outboxWriter;
  private final ObjectMapper objectMapper;

  public TransferJourneyClosureCommands(
      @Lazy TransferStore store,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock,
      OutboxWriter outboxWriter,
      ObjectMapper objectMapper) {
    this.store = Objects.requireNonNull(store, "Transfer store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
    this.outboxWriter = Objects.requireNonNull(outboxWriter, "outbox writer");
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
  }

  public TransferJourneyClosureCommandResult closeUnfulfilled(
      ExecutionContext context,
      InternalId journeyId,
      String reasonCode,
      Instant occurredAt,
      long expectedJourneyVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(journeyId, "Journey id");
    Objects.requireNonNull(occurredAt, "occurrence time");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireOriginScope(context.scope());
    validateReason(reasonCode);
    if (expectedJourneyVersion < 1) {
      throw invalid("A positive expected Journey version is required.");
    }
    requireAuthority(context, journeyId);
    CommandReceiptKey receiptKey = commandKey(context, journeyId, idempotencyKey);
    Optional<TransferStore.ReceiptClaim> previous;
    try {
      previous = store.findReceipt(receiptKey, fingerprint);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
    if (previous.isPresent()) {
      TransferJourneyClosureCommandResult replay = replay(journeyId, previous.orElseThrow());
      if (replay != null) {
        return replay;
      }
    }

    return execute(
        () -> {
          Instant recordedAt = clock.instant();
          store.configureBoundedReceiptWait();
          TransferStore.ReceiptClaim claim =
              store.claimReceipt(receiptKey, fingerprint, recordedAt);
          TransferJourneyClosureCommandResult replay = replay(journeyId, claim);
          if (replay != null) {
            return replay;
          }
          TransferJourney journey =
              store.lockJourney(journeyId).orElseThrow(TransferJourneyClosureCommands::notFound);
          requireOriginTarget(context, journey);
          if (journey.version() != expectedJourneyVersion) {
            throw stateConflict();
          }
          if (store.findJourneyClosure(journeyId).isPresent()
              || !store.canCloseJourneyUnfulfilled(journeyId)) {
            throw stateConflict();
          }
          long nextVersion = Math.addExact(journey.version(), 1);
          TransferJourneyClosure closure =
              new TransferJourneyClosure(
                  journey.id(),
                  journey.referralId(),
                  reasonCode,
                  context.actor(),
                  TransferAuthorities.closure().code(),
                  journey.originOrganization(),
                  journey.originFacility(),
                  occurredAt,
                  recordedAt,
                  nextVersion);
          if (!store.recordJourneyClosure(closure, journey.version())) {
            throw stateConflict();
          }
          auditMutation(context, closure);
          recordUnfulfilledEvent(context, closure);
          store.completeReceipt(
              receiptKey, HttpStatus.CREATED.value(), journey.id(), nextVersion, recordedAt);
          return new TransferJourneyClosureCommandResult(closure, false);
        });
  }

  private TransferJourneyClosureCommandResult replay(
      InternalId journeyId, TransferStore.ReceiptClaim claim) {
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
        if (!journeyId.equals(claim.resultReference().orElseThrow())) {
          throw stateConflict();
        }
        TransferJourneyClosure closure =
            store
                .findJourneyClosure(journeyId)
                .orElseThrow(TransferJourneyClosureCommands::unavailable);
        if (closure.journeyVersion() != claim.resultVersion().orElseThrow()) {
          throw unavailable();
        }
        yield new TransferJourneyClosureCommandResult(closure, true);
      }
    };
  }

  private void requireAuthority(ExecutionContext context, InternalId journeyId) {
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, TransferAuthorities.closure().code());
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    recordDenial(context, journeyId, unavailable);
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer authorization is temporarily unavailable."
            : "The actor is not authorized to close this TransferJourney.");
  }

  private void auditMutation(ExecutionContext context, TransferJourneyClosure closure) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              new AuditEvidence(
                  AuditActionKind.PROTECTED_MUTATION,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  "TRANSFER_JOURNEY_CLOSE",
                  "TransferJourney",
                  Optional.of(closure.journeyId()),
                  closure.recordedAt(),
                  AuditOutcome.SUCCEEDED,
                  Optional.of(closure.reasonCode()),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Journey closure and its required audit evidence could not be committed.");
    }
  }

  private void recordUnfulfilledEvent(ExecutionContext context, TransferJourneyClosure closure) {
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", closure.referralId().toString());
    payload.put("journeyReference", closure.journeyId().toString());
    payload.put("outcome", "UNFULFILLED");
    outboxWriter.record(
        new EventEnvelope(
            InternalId.generate(),
            EventType.parse("transfer.unfulfilled.v1"),
            "transfer",
            new OccurredAt(closure.occurredAt()),
            new RecordedAt(closure.recordedAt()),
            context,
            Optional.of(
                new AggregatePosition(
                    "transfer-journey/" + closure.journeyId(), closure.journeyVersion())),
            payload));
  }

  private void recordDenial(ExecutionContext context, InternalId journeyId, boolean unavailable) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  "TRANSFER_JOURNEY_CLOSE_DENIED",
                  "TransferJourney",
                  Optional.of(journeyId),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of(
                      unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId()));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private CommandReceiptKey commandKey(
      ExecutionContext context, InternalId journeyId, String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context, "transfer.journey.close", "transfer-journeys/" + journeyId, idempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw invalid("A valid Idempotency-Key is required for this Transfer command.");
    }
  }

  private <T> T execute(java.util.function.Supplier<T> operation) {
    try {
      return transaction.execute(status -> operation.get());
    } catch (CannotAcquireLockException busy) {
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DuplicateKeyException conflict) {
      throw stateConflict();
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private static void requireOriginScope(ExecutionScope scope) {
    if (scope.kind() == ExecutionScope.Kind.PLATFORM
        || scope.organization().isEmpty()
        || !DIRECTORY_ISSUER.equals(scope.organization().orElseThrow().issuer().value())
        || scope
            .facility()
            .filter(facility -> !DIRECTORY_ISSUER.equals(facility.issuer().value()))
            .isPresent()) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Journey closure requires a validated Directory origin scope.");
    }
    InternalId.parse(scope.organization().orElseThrow().value());
    scope.facility().ifPresent(facility -> InternalId.parse(facility.value()));
  }

  private static void requireOriginTarget(ExecutionContext context, TransferJourney journey) {
    if (!journey.originOrganization().equals(context.scope().organization().orElseThrow())
        || (context.scope().facility().isPresent()
            && !journey.originFacility().equals(context.scope().facility()))) {
      throw notFound();
    }
  }

  private static void validateReason(String reasonCode) {
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw invalid("A valid structured closure reason code is required.");
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

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.TRANSFER_NOT_FOUND,
        "The TransferJourney was not found in the authorized scope.");
  }

  private static PlatformProblemException stateConflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_STATE_CONFLICT,
        "The Journey changed or still has an active or unreconciled Transfer outcome.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "The TransferJourney closure could not be committed safely.");
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSFER_REQUEST_INVALID, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
