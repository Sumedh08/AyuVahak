package org.ayuvahak.capacity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.ayuvahak.capacity.domain.CapacityAuthorities;
import org.ayuvahak.capacity.domain.CapacityDimension;
import org.ayuvahak.capacity.domain.CapacityDimensionDefinition;
import org.ayuvahak.capacity.domain.CapacityPolicy;
import org.ayuvahak.capacity.domain.CapacityPolicyDefinition;
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
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
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

/** Version and audit owner for deployment-governed Capacity policy. */
@Service
@Lazy
public class CapacityPolicyAdministration {

  private static final String COMMAND_BUSY_DETAIL =
      "A matching Capacity command is still being processed; retry with the same idempotency key.";

  private final CapacityPolicyStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public CapacityPolicyAdministration(
      @Lazy CapacityPolicyStore store,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "capacity policy store");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transaction manager"));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public CapacityVersionCommandResult defineDimension(
      ExecutionContext context,
      CapacityDimensionDefinition definition,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requirePolicyAuthority(context, "CAPACITY_DIMENSION_DEFINE", "CapacityDimension");
    if (expectedVersion < 0) {
      throw invalid("Expected dimension version cannot be negative.");
    }
    CommandReceiptKey receiptKey =
        commandKey(context, "capacity.dimension.define", definition.code(), idempotencyKey);
    return runTransaction(
        () -> {
          CapacityPolicyStore.ReceiptClaim claim = claim(receiptKey, fingerprint);
          if (claim.state() == CapacityPolicyStore.ReceiptState.REPLAY) {
            CapacityDimension replay =
                store
                    .findDimensionVersion(claim.resultReference().orElseThrow())
                    .orElseThrow(
                        () -> new IllegalStateException("Capacity receipt target is missing"));
            return result(replay.revisionId(), replay.code(), replay.version(), claim, true);
          }
          if (claim.state() != CapacityPolicyStore.ReceiptState.OWNER) {
            throw receiptConflict(claim.state());
          }

          Optional<CapacityDimension> current = store.lockCurrentDimension(definition.code());
          long currentVersion = current.map(CapacityDimension::version).orElse(0L);
          if (currentVersion != expectedVersion) {
            throw new CapacityVersionConflictException();
          }
          Instant now = clock.instant();
          CapacityDimension next =
              CapacityDimension.version(
                  InternalId.generate(), currentVersion + 1, definition, context.actor(), now);
          if (current.isEmpty()) {
            store.insertDimensionVersion(next);
          } else {
            store.appendDimensionVersion(next, currentVersion);
          }
          auditMutation(context, "CAPACITY_DIMENSION_DEFINE", "CapacityDimension", next);
          int status = current.isEmpty() ? HttpStatus.CREATED.value() : HttpStatus.OK.value();
          store.completeReceipt(
              receiptKey, status, next.revisionId(), next.version(), clock.instant());
          return new CapacityVersionCommandResult(
              next.revisionId(), next.code(), next.version(), status, false);
        });
  }

  public CapacityVersionCommandResult configurePolicy(
      ExecutionContext context,
      String dimensionCode,
      long expectedPolicyVersion,
      CapacityPolicyDefinition definition,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requirePolicyAuthority(context, "CAPACITY_POLICY_CONFIGURE", "CapacityPolicy");
    if (expectedPolicyVersion < 0) {
      throw invalid("Expected policy version cannot be negative.");
    }
    if (!dimensionCode.equals(definition.dimensionCode())) {
      throw invalid("Policy dimension code must match its target.");
    }
    CommandReceiptKey receiptKey =
        commandKey(context, "capacity.policy.configure", dimensionCode, idempotencyKey);
    return runTransaction(
        () -> {
          CapacityPolicyStore.ReceiptClaim claim = claim(receiptKey, fingerprint);
          if (claim.state() == CapacityPolicyStore.ReceiptState.REPLAY) {
            CapacityPolicy replay =
                store
                    .findPolicyVersion(claim.resultReference().orElseThrow())
                    .orElseThrow(
                        () -> new IllegalStateException("Capacity receipt target is missing"));
            return result(
                replay.revisionId(), replay.dimensionCode(), replay.version(), claim, true);
          }
          if (claim.state() != CapacityPolicyStore.ReceiptState.OWNER) {
            throw receiptConflict(claim.state());
          }

          CapacityDimension dimension =
              store
                  .lockCurrentDimension(dimensionCode)
                  .orElseThrow(
                      () ->
                          new PlatformProblemException(
                              HttpStatus.NOT_FOUND,
                              PlatformErrorCode.CAPACITY_DIMENSION_NOT_FOUND,
                              "The configured Capacity dimension does not exist."));
          try {
            definition.validateFor(dimension);
          } catch (IllegalArgumentException invalid) {
            throw invalid("The source or readiness policy is incompatible with its dimension.");
          }
          Optional<CapacityPolicy> current = store.lockCurrentPolicy(dimensionCode);
          long currentVersion = current.map(CapacityPolicy::version).orElse(0L);
          if (currentVersion != expectedPolicyVersion) {
            throw new CapacityVersionConflictException();
          }
          Instant now = clock.instant();
          CapacityPolicy next =
              CapacityPolicy.version(
                  InternalId.generate(), currentVersion + 1, definition, context.actor(), now);
          if (current.isEmpty()) {
            store.insertPolicyVersion(next);
          } else {
            store.appendPolicyVersion(next, currentVersion);
          }
          auditMutation(context, "CAPACITY_POLICY_CONFIGURE", "CapacityPolicy", next);
          int status = current.isEmpty() ? HttpStatus.CREATED.value() : HttpStatus.OK.value();
          store.completeReceipt(
              receiptKey, status, next.revisionId(), next.version(), clock.instant());
          return new CapacityVersionCommandResult(
              next.revisionId(), next.dimensionCode(), next.version(), status, false);
        });
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
                  "CAPACITY_POLICY_SCOPE_REJECTED",
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
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private <T> T runTransaction(Supplier<T> command) {
    try {
      return transaction.execute(status -> command.get());
    } catch (CapacityVersionConflictException conflict) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.CAPACITY_VERSION_CONFLICT,
          "The expected Capacity version is not current.");
    } catch (CannotAcquireLockException busy) {
      throw problem(
          HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
    } catch (DuplicateKeyException conflict) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.CAPACITY_VERSION_CONFLICT,
          "The Capacity version conflicts with a concurrent change.");
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The required Capacity audit record could not be committed.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.CAPACITY_UNAVAILABLE,
          "The Capacity command could not be committed safely.");
    }
  }

  private CapacityPolicyStore.ReceiptClaim claim(
      CommandReceiptKey receiptKey, RequestFingerprint fingerprint) {
    store.configureBoundedReceiptWait();
    CapacityPolicyStore.ReceiptClaim claim =
        store.claimReceipt(receiptKey, fingerprint, clock.instant());
    if (claim.state() == CapacityPolicyStore.ReceiptState.KEY_REUSED) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
          "The idempotency key was already used for a different Capacity command.");
    }
    if (claim.state() == CapacityPolicyStore.ReceiptState.IN_PROGRESS) {
      throw problem(
          HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
    }
    return claim;
  }

  private void requirePolicyAuthority(
      ExecutionContext context, String actionCode, String targetType) {
    AuthorizationDecision decision =
        authorization
            .getObject()
            .evaluate(context, CapacityAuthorities.policyAdministration().code());
    if (decision.isAllowed()) {
      return;
    }
    recordDenial(context, actionCode, targetType);
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
        "The authenticated actor is not authorized to administer Capacity policy.");
  }

  private void auditMutation(
      ExecutionContext context, String actionCode, String targetType, CapacityDimension dimension) {
    auditWriter
        .getObject()
        .recordMutation(
            evidence(
                context,
                AuditActionKind.PROTECTED_MUTATION,
                actionCode,
                targetType,
                dimension.revisionId(),
                dimension.reasonCode(),
                AuditAuthorizationOutcome.ALLOWED));
  }

  private void auditMutation(
      ExecutionContext context, String actionCode, String targetType, CapacityPolicy policy) {
    auditWriter
        .getObject()
        .recordMutation(
            evidence(
                context,
                AuditActionKind.PROTECTED_MUTATION,
                actionCode,
                targetType,
                policy.revisionId(),
                policy.reasonCode(),
                AuditAuthorizationOutcome.ALLOWED));
  }

  private void recordDenial(ExecutionContext context, String actionCode, String targetType) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              evidence(
                  context,
                  AuditActionKind.SECURITY_OUTCOME,
                  actionCode,
                  targetType,
                  null,
                  "AUTHORIZATION_DENIED",
                  AuditAuthorizationOutcome.DENIED));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private AuditEvidence evidence(
      ExecutionContext context,
      AuditActionKind kind,
      String actionCode,
      String targetType,
      InternalId targetId,
      String reasonCode,
      AuditAuthorizationOutcome authorizationOutcome) {
    return new AuditEvidence(
        kind,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        actionCode,
        targetType,
        Optional.ofNullable(targetId),
        clock.instant(),
        kind == AuditActionKind.PROTECTED_MUTATION ? AuditOutcome.SUCCEEDED : AuditOutcome.DENIED,
        Optional.of(reasonCode),
        authenticationEvidence(context.authenticationMechanism()),
        authorizationOutcome,
        context.correlationId());
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
      ExecutionContext context, String operation, String target, String rawKey) {
    try {
      return CommandReceiptKey.from(context, operation, target, rawKey);
    } catch (IllegalArgumentException invalid) {
      throw invalid("A valid Idempotency-Key is required for this Capacity command.");
    }
  }

  private static CapacityVersionCommandResult result(
      InternalId id,
      String code,
      long version,
      CapacityPolicyStore.ReceiptClaim claim,
      boolean replayed) {
    return new CapacityVersionCommandResult(
        id, code, version, claim.responseStatus().orElseThrow(), replayed);
  }

  private static PlatformProblemException receiptConflict(CapacityPolicyStore.ReceiptState state) {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
        state == CapacityPolicyStore.ReceiptState.KEY_REUSED
            ? "The idempotency key cannot be reused for a different Capacity command."
            : COMMAND_BUSY_DETAIL);
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.CAPACITY_VALUE_INVALID, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
