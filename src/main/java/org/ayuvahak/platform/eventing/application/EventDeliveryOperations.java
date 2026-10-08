package org.ayuvahak.platform.eventing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
import org.ayuvahak.platform.authorization.domain.PhaseTwoAuthorities;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorized, audited operator operations over internal outbox delivery metadata. */
@Service
@Lazy
public class EventDeliveryOperations {

  private static final int DEFAULT_PAGE_SIZE = 50;
  private static final int MAX_PAGE_SIZE = 100;

  private final EventDeliveryOperationsStore store;
  private final EventHandlerRegistry handlers;
  private final EventEnvelopeJsonCodec codec;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final ObjectProvider<PlatformTransactionManager> transactionManagers;
  private final Clock clock;

  public EventDeliveryOperations(
      @Lazy EventDeliveryOperationsStore store,
      EventHandlerRegistry handlers,
      EventEnvelopeJsonCodec codec,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      ObjectProvider<PlatformTransactionManager> transactionManagers,
      @Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.handlers = Objects.requireNonNull(handlers, "handlers");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
    this.transactionManagers = Objects.requireNonNull(transactionManagers, "transactionManagers");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public void recordInvalidScope(
      org.ayuvahak.platform.core.ActorReference actor, CorrelationId correlationId) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(actor),
                  Optional.empty(),
                  "EXECUTION_SCOPE_REJECTED",
                  "ExecutionScope",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of("INVALID_SCOPE"),
                  actor.kind() == ActorKind.HUMAN
                      ? AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN
                      : AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN,
                  AuditAuthorizationOutcome.NOT_EVALUATED,
                  correlationId));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  public EventDeliveryPage list(
      ExecutionContext context, Optional<String> cursor, int requestedLimit) {
    requireAuthority(context, "EVENT_DELIVERY_LIST", Optional.empty());
    int limit = requestedLimit == 0 ? DEFAULT_PAGE_SIZE : requestedLimit;
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "Page size must be between 1 and 100.");
    }
    Optional<UUID> afterId =
        cursor.map(
            value -> {
              try {
                return UUID.fromString(value);
              } catch (IllegalArgumentException invalid) {
                throw problem(
                    HttpStatus.BAD_REQUEST,
                    PlatformErrorCode.VALIDATION_FAILED,
                    "The page cursor is invalid.");
              }
            });
    recordRead(context);
    try {
      List<EventDeliverySummary> rows = store.listCurrent(afterId, limit + 1);
      boolean hasMore = rows.size() > limit;
      List<EventDeliverySummary> page = hasMore ? rows.subList(0, limit) : rows;
      Optional<String> next =
          hasMore ? Optional.of(page.getLast().eventId().toString()) : Optional.empty();
      return new EventDeliveryPage(page, next);
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.EVENT_DELIVERY_UNAVAILABLE,
          "Event delivery status is temporarily unavailable.");
    }
  }

  public ReplayResult replay(
      ExecutionContext context,
      InternalId eventId,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(context, "EVENT_DELIVERY_REPLAY", Optional.of(eventId));
    CommandReceiptKey key;
    try {
      key =
          CommandReceiptKey.from(
              context, "event-delivery.replay", eventId.toString(), rawIdempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "A valid Idempotency-Key is required for replay.");
    }

    try {
      return new TransactionTemplate(transactionManagers.getObject())
          .execute(
              status -> {
                store.configureBoundedReceiptWait();
                DeliveryReceiptClaim claim =
                    store.claimReceipt(
                        key.scopeDigest(),
                        key.idempotencyKey().value(),
                        fingerprint.value(),
                        clock.instant());
                if (claim.state() == DeliveryReceiptClaim.State.KEY_REUSED) {
                  throw problem(
                      HttpStatus.CONFLICT,
                      PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                      "The idempotency key was already used for a different command.");
                }
                if (claim.state() == DeliveryReceiptClaim.State.IN_PROGRESS) {
                  throw problem(
                      HttpStatus.CONFLICT,
                      PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
                      "A matching replay is still being processed; retry with the same key.");
                }
                if (claim.state() == DeliveryReceiptClaim.State.REPLAY) {
                  return new ReplayResult(
                      claim.eventId().orElseThrow(), claim.generation().orElseThrow(), true);
                }

                ReplayCandidate candidate =
                    store
                        .findCurrent(eventId)
                        .orElseThrow(
                            () ->
                                problem(
                                    HttpStatus.NOT_FOUND,
                                    PlatformErrorCode.EVENT_NOT_FOUND,
                                    "The requested event delivery was not found."));
                if (!candidate.state().equals("STALLED")
                    && !candidate.state().equals("UNHANDLED")) {
                  throw problem(
                      HttpStatus.CONFLICT,
                      PlatformErrorCode.EVENT_NOT_REPLAYABLE,
                      "Only stalled or unhandled event deliveries can be replayed.");
                }
                if (!hasCompatibleHandler(candidate)) {
                  throw problem(
                      HttpStatus.CONFLICT,
                      PlatformErrorCode.EVENT_HANDLER_UNAVAILABLE,
                      "No compatible internal handler is registered for this event.");
                }
                Instant now = clock.instant();
                int generation =
                    store
                        .replay(eventId, candidate.generation(), now)
                        .orElseThrow(
                            () ->
                                problem(
                                    HttpStatus.CONFLICT,
                                    PlatformErrorCode.EVENT_NOT_REPLAYABLE,
                                    "The delivery changed and is no longer eligible for replay."));
                recordMutation(context, eventId);
                store.completeReceipt(
                    key.scopeDigest(),
                    key.idempotencyKey().value(),
                    eventId,
                    generation,
                    clock.instant());
                return new ReplayResult(eventId, generation, false);
              });
    } catch (CannotAcquireLockException busy) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
          "A matching replay is still being processed; retry with the same key.");
    } catch (AuditPersistenceUnavailableException auditFailure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The replay was not accepted because its audit record is unavailable.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.EVENT_DELIVERY_UNAVAILABLE,
          "The replay could not be committed safely.");
    }
  }

  private boolean hasCompatibleHandler(ReplayCandidate candidate) {
    var registered = handlers.handlersFor(candidate.eventType());
    if (registered.isEmpty()) return false;
    for (var handler : registered) {
      try {
        codec.requireCompatible(candidate.envelopeJson(), handler.supportedEventTypes());
        return true;
      } catch (IllegalArgumentException incompatible) {
        // Another registered handler may support this exact contract.
      }
    }
    return false;
  }

  private void requireAuthority(
      ExecutionContext context, String action, Optional<InternalId> target) {
    AuthorizationDecision decision =
        authorization
            .getObject()
            .evaluate(context, PhaseTwoAuthorities.EVENT_DELIVERY_OPERATIONS.code());
    if (decision.isAllowed()) return;
    recordDenial(context, action, target);
    if (decision.denialReason().orElse(null)
        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Authorization could not be evaluated safely.");
    }
    throw problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated principal is not authorized for this operation.");
  }

  private void recordRead(ExecutionContext context) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SENSITIVE_READ,
                  "EVENT_DELIVERY_LIST",
                  Optional.empty(),
                  AuditOutcome.SUCCEEDED,
                  AuditAuthorizationOutcome.ALLOWED));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Event delivery status was not disclosed because its audit record is unavailable.");
    }
  }

  private void recordDenial(ExecutionContext context, String action, Optional<InternalId> target) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SECURITY_OUTCOME,
                  action,
                  target,
                  AuditOutcome.DENIED,
                  AuditAuthorizationOutcome.DENIED));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private void recordMutation(ExecutionContext context, InternalId eventId) {
    auditWriter
        .getObject()
        .recordMutation(
            evidence(
                context,
                AuditActionKind.PROTECTED_MUTATION,
                "EVENT_DELIVERY_REPLAY",
                Optional.of(eventId),
                AuditOutcome.SUCCEEDED,
                AuditAuthorizationOutcome.ALLOWED));
  }

  private AuditEvidence evidence(
      ExecutionContext context,
      AuditActionKind kind,
      String action,
      Optional<InternalId> target,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome) {
    return new AuditEvidence(
        kind,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        action,
        "InternalEvent",
        target,
        clock.instant(),
        outcome,
        Optional.empty(),
        auditAuthenticationEvidence(context.authenticationMechanism()),
        authorizationOutcome,
        context.correlationId());
  }

  private static AuditAuthenticationEvidence auditAuthenticationEvidence(
      AuthenticationMechanism mechanism) {
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String message) {
    return new PlatformProblemException(status, code, message);
  }

  public record ReplayResult(InternalId eventId, int deliveryGeneration, boolean replayed) {}
}
