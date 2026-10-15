package org.ayuvahak.transport.application;

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
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transport.adapter.configuration.TransportProperties;
import org.ayuvahak.transport.domain.TransportAuthorities;
import org.ayuvahak.transport.domain.TransportRequest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates a manual-first Transport request after independent Transport and Transfer checks. */
@Service
public final class TransportRequestCommands {

  private static final String BUSY_DETAIL =
      "A matching Transport command is still being processed; retry with the same idempotency key.";

  private final TransportRequestStore store;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransferRequestEligibilityPort transferEligibility;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final TransportProperties properties;

  public TransportRequestCommands(
      TransportRequestStore store,
      AuthorizationService authorization,
      AuditWriter audit,
      TransferRequestEligibilityPort transferEligibility,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock,
      TransportProperties properties) {
    this.store = Objects.requireNonNull(store, "Transport request store");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transferEligibility =
        Objects.requireNonNull(transferEligibility, "Transfer eligibility port");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
    this.properties = Objects.requireNonNull(properties, "Transport properties");
  }

  public TransportRequestResult create(
      ExecutionContext serviceContext,
      InternalId transferId,
      ActorReference initiatingActor,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(serviceContext, "service execution context");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(initiatingActor, "initiating actor");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireConfiguredServiceScope(serviceContext);
    authorize(serviceContext, transferId);
    CommandReceiptKey receiptKey = commandKey(serviceContext, transferId, idempotencyKey);
    try {
      return transaction.execute(
          ignored ->
              createWithinTransaction(
                  serviceContext, transferId, initiatingActor, receiptKey, fingerprint));
    } catch (CannotAcquireLockException busy) {
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DuplicateKeyException conflict) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSPORT_ACTIVE_REQUEST_EXISTS,
          "An active Transport request already exists for this Transfer.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSPORT_UNAVAILABLE,
          "Transport could not persist the request safely.");
    }
  }

  private TransportRequestResult createWithinTransaction(
      ExecutionContext context,
      InternalId transferId,
      ActorReference initiatingActor,
      CommandReceiptKey receiptKey,
      RequestFingerprint fingerprint) {
    Instant now = clock.instant();
    store.configureBoundedReceiptWait();
    TransportRequestStore.Claim claim = store.claimReceipt(receiptKey, fingerprint, now);
    switch (claim.state()) {
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The Idempotency-Key was already used for a different Transport command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
      case REPLAY -> {
        TransportRequest request =
            store
                .find(claim.resultReference().orElseThrow())
                .orElseThrow(TransportRequestCommands::unavailable);
        if (!request.transferId().equals(transferId)) {
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
              "The Transport command receipt does not match the requested Transfer.");
        }
        return result(request, true);
      }
      case OWNER -> {
        // Continue below while this owner-local receipt is protected by the same DB transaction.
      }
    }

    var origin = context.scope().facility().orElseThrow();
    TransportRequestEligibility eligibility =
        transferEligibility.check(context, transferId, origin);
    if (eligibility.outcome() == TransportRequestEligibility.Outcome.UNAVAILABLE) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSFER_UNAVAILABLE,
          "Transfer eligibility could not be verified; no Transport request was created.");
    }
    if (eligibility.outcome() != TransportRequestEligibility.Outcome.ELIGIBLE) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSPORT_NOT_ELIGIBLE,
          "The Transfer is not currently eligible for a Transport request.");
    }
    if (eligibility.originFacility().filter(origin::equals).isEmpty()) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSPORT_NOT_ELIGIBLE,
          "Transfer returned an origin that does not match the validated execution scope.");
    }

    TransportRequest request =
        new TransportRequest(
            InternalId.generate(),
            transferId,
            origin,
            eligibility.destinationFacility().orElseThrow(),
            initiatingActor,
            context.actor(),
            TransportRequest.Status.REQUESTED,
            now,
            context.correlationId(),
            1);
    if (!store.insert(
        request,
        initiatingActor.identity().issuer().value(),
        initiatingActor.identity().resourceType(),
        context.actor().identity().issuer().value(),
        context.actor().identity().resourceType())) {
      throw unavailable();
    }
    auditMutation(context, request);
    store.completeReceipt(receiptKey, request.id(), now);
    return result(request, false);
  }

  private void authorize(ExecutionContext context, InternalId transferId) {
    AuthorizationDecision decision =
        authorization.evaluate(context, TransportAuthorities.request().code());
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
              "TRANSPORT_REQUEST_DENIED",
              "TransportRequest",
              Optional.of(transferId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
              AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
              AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transport withheld the request because its denial audit is unavailable.");
    }
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transport authorization is temporarily unavailable."
            : "The Transfer service is not authorized to request Transport at this scope.");
  }

  private void auditMutation(ExecutionContext context, TransportRequest request) {
    try {
      audit.recordMutation(
          new AuditEvidence(
              AuditActionKind.PROTECTED_MUTATION,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSPORT_REQUEST_CREATE",
              "TransportRequest",
              Optional.of(request.id()),
              request.requestedAt(),
              AuditOutcome.SUCCEEDED,
              Optional.of("MANUAL_COORDINATION_REQUIRED"),
              AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
              AuditAuthorizationOutcome.ALLOWED,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(
                      context.scope().kind(),
                      context.scope().organization(),
                      context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Transport request and its required audit evidence could not be committed.");
    }
  }

  private void requireConfiguredServiceScope(ExecutionContext context) {
    if (context.actor().kind() != ActorKind.SERVICE
        || !properties.getWorkerClientId().equals(context.actor().identity().value())
        || context.authenticationMechanism() != AuthenticationMechanism.CONFIGURED_SERVICE_IDENTITY
        || context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transport request creation requires its configured service identity and facility scope.");
    }
  }

  private static TransportRequestResult result(TransportRequest request, boolean replayed) {
    return new TransportRequestResult(
        request.id(),
        request.transferId(),
        request.status().name(),
        "MANUAL_COORDINATION_REQUIRED",
        replayed);
  }

  private static CommandReceiptKey commandKey(
      ExecutionContext context, InternalId transferId, String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context, "transport.request.create", "transfers/" + transferId, idempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSPORT_REQUEST_INVALID,
          "A valid Idempotency-Key is required for this Transport command.");
    }
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSPORT_UNAVAILABLE,
        "The Transport request result could not be recovered safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  public record TransportRequestResult(
      InternalId transportRequestId,
      InternalId transferId,
      String status,
      String coordinationOutcome,
      boolean commandReplayed) {}
}
