package org.ayuvahak.platform.authorization.application;

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
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.authorization.domain.AccessGrant;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.authorization.domain.GrantStatus;
import org.ayuvahak.platform.authorization.domain.PhaseTwoAuthorities;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Application owner for platform grant changes, audits, publication intent, and retry receipts. */
@Service
@Lazy
public class AccessGrantAdministration {

  private static final int DEFAULT_PAGE_SIZE = 50;
  private static final int MAX_PAGE_SIZE = 100;
  private static final String COMMAND_BUSY_DETAIL =
      "A matching command is still being processed; retry with the same idempotency key.";

  private final AccessGrantStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final OutboxWriter outboxWriter;
  private final FunctionalAuthorityCatalog authorities;
  private final AuthenticationProperties authenticationProperties;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public AccessGrantAdministration(
      @Lazy AccessGrantStore store,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      OutboxWriter outboxWriter,
      FunctionalAuthorityCatalog authorities,
      AuthenticationProperties authenticationProperties,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock,
      ObjectMapper objectMapper) {
    this.store = Objects.requireNonNull(store, "store");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.outboxWriter = Objects.requireNonNull(outboxWriter, "outbox writer");
    this.authorities = Objects.requireNonNull(authorities, "authorities");
    this.authenticationProperties =
        Objects.requireNonNull(authenticationProperties, "authentication properties");
    this.transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transaction manager"));
    this.clock = Objects.requireNonNull(clock, "clock");
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
  }

  public GrantMutationResult create(
      ExecutionContext context,
      GrantPrincipal principal,
      String authorityCode,
      String reasonCode,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(context, "ACCESS_GRANT_CREATE", Optional.empty());
    validateGrantee(context, principal, authorityCode, reasonCode, true);
    CommandReceiptKey key =
        commandKey(context, "access-grant.create", "platform-access-grants", rawIdempotencyKey);
    return withCommandTransaction(
        key,
        fingerprint,
        () -> {
          if (store.databaseContainsGrant(principal.actorReference(), authorityCode)) {
            throw problem(
                HttpStatus.CONFLICT,
                PlatformErrorCode.GRANT_ALREADY_EXISTS,
                "An active grant already exists for this principal, authority, and scope.");
          }
          InternalId id =
              store.insertPlatformGrant(principal.actorReference(), authorityCode, clock.instant());
          long sequence = store.advanceEventSequence(id);
          auditMutation(context, "ACCESS_GRANT_CREATE", id, reasonCode);
          publishGrantEvent(context, id, authorityCode, sequence, "grant-created");
          return new GrantMutationResult(HttpStatus.CREATED.value(), id, false);
        });
  }

  public GrantMutationResult revoke(
      ExecutionContext context,
      InternalId grantId,
      String reasonCode,
      String rawIdempotencyKey,
      RequestFingerprint fingerprint) {
    requireAuthority(context, "ACCESS_GRANT_REVOKE", Optional.of(grantId));
    if (reasonCode == null
        || !List.of("ACCESS_REMOVAL", "ACCESS_CHANGE", "SECURITY_RESPONSE", "OTHER")
            .contains(reasonCode)) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "A valid reason code is required.");
    }
    CommandReceiptKey key =
        commandKey(context, "access-grant.revoke", grantId.toString(), rawIdempotencyKey);
    return withCommandTransaction(
        key,
        fingerprint,
        () -> {
          AccessGrant grant =
              store
                  .findGrant(grantId)
                  .orElseThrow(
                      () ->
                          problem(
                              HttpStatus.NOT_FOUND,
                              PlatformErrorCode.GRANT_NOT_FOUND,
                              "The requested grant was not found."));
          if (grant.status() != GrantStatus.ACTIVE) {
            throw problem(
                HttpStatus.CONFLICT,
                PlatformErrorCode.GRANT_NOT_ACTIVE,
                "The requested grant is not active.");
          }
          if (grant.principal().equals(context.actor())) {
            recordDenial(context, "ACCESS_GRANT_REVOKE", Optional.of(grantId), "SELF_GRANT_CHANGE");
            throw problem(
                HttpStatus.FORBIDDEN,
                PlatformErrorCode.AUTHORIZATION_DENIED,
                "An administrator cannot alter their own grant.");
          }
          long sequence =
              store
                  .revoke(grantId, clock.instant())
                  .orElseThrow(
                      () ->
                          problem(
                              HttpStatus.CONFLICT,
                              PlatformErrorCode.GRANT_NOT_ACTIVE,
                              "The requested grant is no longer active."));
          auditMutation(context, "ACCESS_GRANT_REVOKE", grantId, reasonCode);
          publishGrantEvent(context, grantId, grant.authorityCode(), sequence, "grant-revoked");
          return new GrantMutationResult(HttpStatus.NO_CONTENT.value(), grantId, false);
        });
  }

  public GrantPage list(ExecutionContext context, Optional<String> cursor, int requestedLimit) {
    requireAuthority(context, "ACCESS_GRANT_LIST", Optional.empty());
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
              } catch (IllegalArgumentException exception) {
                throw problem(
                    HttpStatus.BAD_REQUEST,
                    PlatformErrorCode.VALIDATION_FAILED,
                    "The page cursor is invalid.");
              }
            });
    recordRead(context, "ACCESS_GRANT_LIST");
    List<AccessGrant> rows = store.listPlatformGrants(afterId, limit + 1);
    boolean hasMore = rows.size() > limit;
    List<AccessGrant> page = hasMore ? rows.subList(0, limit) : rows;
    Optional<String> next =
        hasMore ? Optional.of(page.getLast().id().toString()) : Optional.empty();
    return new GrantPage(page, next);
  }

  public void recordInvalidScope(ActorReference actor, CorrelationId correlationId) {
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

  public void validateTrustedPrincipal(GrantPrincipal principal) {
    if (!authenticationProperties.issuer().equals(principal.issuer().value())) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "The principal issuer is not configured as trusted.");
    }
    if (principal.kind() == ActorKind.SERVICE
        && !authenticationProperties.serviceClientIds().contains(principal.identifier())) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "The service principal is not in the configured client allowlist.");
    }
  }

  private GrantMutationResult withCommandTransaction(
      CommandReceiptKey key,
      RequestFingerprint fingerprint,
      java.util.function.Supplier<GrantMutationResult> mutation) {
    try {
      return transaction.execute(
          status -> {
            store.configureBoundedReceiptWait();
            Instant now = clock.instant();
            AccessGrantStore.ReceiptClaim claim =
                store.claimReceipt(
                    key.scopeDigest(), key.idempotencyKey().value(), fingerprint.value(), now);
            if (claim.state() == AccessGrantStore.ReceiptState.KEY_REUSED) {
              throw problem(
                  HttpStatus.CONFLICT,
                  PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                  "The idempotency key was already used for a different command.");
            }
            if (claim.state() == AccessGrantStore.ReceiptState.IN_PROGRESS) {
              throw problem(
                  HttpStatus.CONFLICT,
                  PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
                  COMMAND_BUSY_DETAIL);
            }
            if (claim.state() == AccessGrantStore.ReceiptState.REPLAY) {
              return new GrantMutationResult(
                  claim.responseStatus().orElseThrow(), claim.result().orElseThrow(), true);
            }
            GrantMutationResult result = mutation.get();
            store.completeReceipt(
                key.scopeDigest(),
                key.idempotencyKey().value(),
                result.status(),
                result.grantId(),
                clock.instant());
            return result;
          });
    } catch (CannotAcquireLockException failure) {
      throw problem(
          HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
    } catch (DuplicateKeyException failure) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.GRANT_ALREADY_EXISTS,
          "An active grant already exists for this principal, authority, and scope.");
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The required audit record could not be committed.");
    } catch (org.springframework.dao.DataAccessException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.EVENT_PUBLICATION_UNAVAILABLE,
          "The grant command could not be committed safely.");
    }
  }

  private void requireAuthority(
      ExecutionContext context, String actionCode, Optional<InternalId> targetId) {
    AuthorizationDecision decision =
        authorization
            .getObject()
            .evaluate(context, PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION.code());
    if (decision.isAllowed()) {
      return;
    }
    recordDenial(context, actionCode, targetId, "AUTHORIZATION_DENIED");
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

  private void validateGrantee(
      ExecutionContext context,
      GrantPrincipal principal,
      String authorityCode,
      String reasonCode,
      boolean creation) {
    validateTrustedPrincipal(principal);
    if (authorities.find(authorityCode).isEmpty()) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "The requested authority is not configured for platform administration.");
    }
    List<String> allowedReasons =
        creation
            ? List.of("ACCESS_SETUP", "ACCESS_CHANGE", "SERVICE_SETUP", "OTHER")
            : List.of("ACCESS_REMOVAL", "ACCESS_CHANGE", "SECURITY_RESPONSE", "OTHER");
    if (!allowedReasons.contains(reasonCode)) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "A valid reason code is required.");
    }
    if (context.actor().equals(principal.actorReference())) {
      recordDenial(
          context,
          creation ? "ACCESS_GRANT_CREATE" : "ACCESS_GRANT_REVOKE",
          Optional.empty(),
          "SELF_GRANT_CHANGE");
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "An administrator cannot grant or change their own access.");
    }
  }

  private void auditMutation(
      ExecutionContext context, String actionCode, InternalId targetId, String reasonCode) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              evidence(
                  context,
                  AuditActionKind.PROTECTED_MUTATION,
                  actionCode,
                  Optional.of(targetId),
                  AuditOutcome.SUCCEEDED,
                  Optional.of(reasonCode),
                  AuditAuthorizationOutcome.ALLOWED));
    } catch (AuditPersistenceUnavailableException failure) {
      throw failure;
    }
  }

  private void recordRead(ExecutionContext context, String actionCode) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SENSITIVE_READ,
                  actionCode,
                  Optional.empty(),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  AuditAuthorizationOutcome.ALLOWED));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The required access record could not be committed.");
    }
  }

  private void recordDenial(
      ExecutionContext context,
      String actionCode,
      Optional<InternalId> targetId,
      String reasonCode) {
    if (context == null) {
      return;
    }
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SECURITY_OUTCOME,
                  actionCode,
                  targetId,
                  AuditOutcome.DENIED,
                  Optional.of(reasonCode),
                  AuditAuthorizationOutcome.DENIED));
    } catch (AuditPersistenceUnavailableException failure) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The denial remains in effect; its required audit record is unavailable.");
    }
  }

  private AuditEvidence evidence(
      ExecutionContext context,
      AuditActionKind kind,
      String actionCode,
      Optional<InternalId> targetId,
      AuditOutcome outcome,
      Optional<String> reasonCode,
      AuditAuthorizationOutcome authorizationOutcome) {
    return new AuditEvidence(
        kind,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        actionCode,
        "AccessGrant",
        targetId,
        clock.instant(),
        outcome,
        reasonCode,
        auditAuthenticationEvidence(context.authenticationMechanism()),
        authorizationOutcome,
        context.correlationId());
  }

  private void publishGrantEvent(
      ExecutionContext context,
      InternalId grantId,
      String authorityCode,
      long sequence,
      String fact) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("grantId", grantId.toString());
    payload.put("authorityCode", authorityCode);
    Instant now = clock.instant();
    outboxWriter.record(
        new EventEnvelope(
            InternalId.generate(),
            EventType.parse("identity-access." + fact + ".v1"),
            "identity-access",
            new OccurredAt(now),
            new RecordedAt(now),
            context,
            Optional.of(new AggregatePosition(grantId.toString(), sequence)),
            payload));
  }

  private CommandReceiptKey commandKey(
      ExecutionContext context, String operation, String target, String rawKey) {
    try {
      return CommandReceiptKey.from(context, operation, target, rawKey);
    } catch (IllegalArgumentException exception) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "The idempotency key is missing or invalid.");
    }
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
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
