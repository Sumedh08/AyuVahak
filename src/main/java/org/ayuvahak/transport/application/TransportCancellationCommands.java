package org.ayuvahak.transport.application;

import java.time.Clock;
import java.time.Duration;
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
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
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
import org.ayuvahak.transport.adapter.configuration.TransportProperties;
import org.ayuvahak.transport.domain.TransportAuthorities;
import org.ayuvahak.transport.domain.TransportCancellationOperation;
import org.ayuvahak.transport.domain.TransportRequest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Audited, idempotent Transport cancellation intent and concrete outcome commands. */
@Service
public final class TransportCancellationCommands {

  private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);
  private static final EventType RESOLVED = EventType.parse("transport.cancellation-resolved.v1");
  private static final String BUSY_DETAIL =
      "A matching Transport cancellation command is still being processed; retry with the same idempotency key.";

  private final TransportRequestStore requests;
  private final TransportEvidenceStore evidence;
  private final TransportCancellationStore cancellations;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final TransportProperties properties;
  private final AuthenticationProperties authenticationProperties;

  public TransportCancellationCommands(
      TransportRequestStore requests,
      TransportEvidenceStore evidence,
      TransportCancellationStore cancellations,
      AuthorizationService authorization,
      AuditWriter audit,
      OutboxWriter outbox,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock,
      TransportProperties properties,
      AuthenticationProperties authenticationProperties) {
    this.requests = Objects.requireNonNull(requests);
    this.evidence = Objects.requireNonNull(evidence);
    this.cancellations = Objects.requireNonNull(cancellations);
    this.authorization = Objects.requireNonNull(authorization);
    this.audit = Objects.requireNonNull(audit);
    this.outbox = Objects.requireNonNull(outbox);
    this.mapper = Objects.requireNonNull(mapper);
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock);
    this.properties = Objects.requireNonNull(properties);
    this.authenticationProperties = Objects.requireNonNull(authenticationProperties);
  }

  public CancellationResult request(
      ExecutionContext context,
      InternalId requestId,
      ActorReference initiatingActor,
      String reasonCode,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireScope(context, requestId);
    requireAuthority(context, requestId);
    validateReason(reasonCode);
    Objects.requireNonNull(initiatingActor, "initiating actor");
    CommandReceiptKey key =
        commandKey(context, "transport.cancellation.request", requestId, idempotencyKey);
    try {
      return transaction.execute(
          ignored -> {
            Instant now = clock.instant();
            requests.configureBoundedReceiptWait();
            TransportCancellationStore.ReceiptClaim claim =
                cancellations.claimReceipt(key, fingerprint, now);
            if (claim.state() == TransportCancellationStore.ClaimState.KEY_REUSED) {
              throw conflict(
                  PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                  "The Idempotency-Key was already used for a different cancellation request.");
            }
            if (claim.state() == TransportCancellationStore.ClaimState.IN_PROGRESS) {
              throw conflict(PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
            }
            if (claim.state() == TransportCancellationStore.ClaimState.REPLAY) {
              TransportCancellationOperation replay =
                  cancellations
                      .find(claim.operationId().orElseThrow())
                      .orElseThrow(TransportCancellationCommands::unavailable);
              requireOperationRequest(replay, requestId);
              return result(
                  replay,
                  requests.find(requestId).orElseThrow(TransportCancellationCommands::notFound),
                  true);
            }

            TransportRequest request = lockRequest(context, requestId);
            if (request.status() == TransportRequest.Status.CANCELLED
                || request.status() == TransportRequest.Status.COMPLETED
                || request.status() == TransportRequest.Status.UNFULFILLED) {
              throw conflict(
                  PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
                  "A terminal Transport request cannot start a cancellation operation.");
            }
            if (cancellations.findUnresolvedForRequest(requestId).isPresent()) {
              throw conflict(
                  PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
                  "An unresolved cancellation operation already blocks this Transport request.");
            }
            InternalId operationId = InternalId.generate();
            boolean movementStarted = movementStarted(request);
            Optional<InternalId> resolutionEventId =
                movementStarted ? Optional.of(InternalId.generate()) : Optional.empty();
            Optional<TransportCancellationOperation.Outcome> initialOutcome =
                movementStarted
                    ? Optional.of(TransportCancellationOperation.Outcome.MOVEMENT_ALREADY_STARTED)
                    : Optional.empty();
            String sourceNamespace = "urn:ayuvahak:transport";
            String sourceId = requestId + "-version-" + request.version();
            var operation =
                new TransportCancellationOperation(
                    operationId,
                    requestId,
                    reasonCode,
                    movementStarted
                        ? TransportCancellationOperation.State.RESOLVED
                        : TransportCancellationOperation.State.PENDING,
                    initialOutcome,
                    now,
                    movementStarted ? Optional.of(now) : Optional.empty(),
                    movementStarted ? Optional.of(sourceNamespace) : Optional.empty(),
                    movementStarted ? Optional.of(sourceId) : Optional.empty(),
                    movementStarted ? Optional.of(fingerprint.value()) : Optional.empty(),
                    movementStarted ? Optional.of(now) : Optional.empty(),
                    movementStarted
                        ? Optional.of(context.correlationId().value())
                        : Optional.empty(),
                    resolutionEventId,
                    movementStarted ? 2 : 1);
            if (!cancellations.insert(
                operation,
                initiatingActor.kind().name(),
                initiatingActor.identity().issuer().value(),
                initiatingActor.identity().resourceType(),
                initiatingActor.identity().value(),
                context.scope().organization().orElseThrow().value(),
                context.scope().facility().orElseThrow().value(),
                context.correlationId().value(),
                TransportAuthorities.cancellation().code())) {
              throw unavailable();
            }
            auditMutation(context, operation, "TRANSPORT_CANCELLATION_REQUESTED", now);
            if (movementStarted) {
              recordResolvedEvent(context, request, operation, sourceNamespace, sourceId, now);
            }
            cancellations.completeReceipt(key, operationId, now);
            return result(operation, request, false);
          });
    } catch (CannotAcquireLockException busy) {
      throw conflict(PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DuplicateKeyException duplicate) {
      throw conflict(
          PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
          "A cancellation operation already exists or its source identity conflicts.");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  /** Marks an unresolved operation as uncertain without claiming a provider/manual outcome. */
  public CancellationResult markUncertain(
      ExecutionContext context,
      InternalId operationId,
      TransportCancellationOperation.State state,
      String reasonCode,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    if (state != TransportCancellationOperation.State.OUTCOME_UNKNOWN
        && state != TransportCancellationOperation.State.RECONCILIATION_REQUIRED) {
      throw conflict(
          PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID,
          "Cancellation uncertainty must be OUTCOME_UNKNOWN or RECONCILIATION_REQUIRED.");
    }
    validateReason(reasonCode);
    requireScope(context, operationId);
    requireAuthority(context, operationId);
    CommandReceiptKey key =
        commandKey(context, "transport.cancellation.uncertain", operationId, idempotencyKey);
    try {
      return transaction.execute(
          ignored -> {
            Instant now = clock.instant();
            requests.configureBoundedReceiptWait();
            TransportCancellationStore.ReceiptClaim claim =
                cancellations.claimReceipt(key, fingerprint, now);
            if (claim.state() == TransportCancellationStore.ClaimState.KEY_REUSED) {
              throw conflict(
                  PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                  "The Idempotency-Key was already used for different cancellation uncertainty.");
            }
            if (claim.state() == TransportCancellationStore.ClaimState.IN_PROGRESS) {
              throw conflict(PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
            }
            if (claim.state() == TransportCancellationStore.ClaimState.REPLAY) {
              TransportCancellationOperation replay =
                  cancellations
                      .find(claim.operationId().orElseThrow())
                      .orElseThrow(TransportCancellationCommands::unavailable);
              TransportRequest request =
                  requests
                      .find(replay.transportRequestId())
                      .orElseThrow(TransportCancellationCommands::notFound);
              return result(replay, request, true);
            }
            TransportCancellationOperation operation =
                cancellations
                    .lock(operationId)
                    .orElseThrow(TransportCancellationCommands::notFound);
            TransportRequest request = lockRequest(context, operation.transportRequestId());
            boolean allowedTransition =
                operation.state() == TransportCancellationOperation.State.PENDING
                    || (operation.state() == TransportCancellationOperation.State.OUTCOME_UNKNOWN
                        && state == TransportCancellationOperation.State.RECONCILIATION_REQUIRED);
            if (!allowedTransition
                || !cancellations.markState(operation.id(), operation.version(), state)) {
              throw conflict(
                  PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
                  "The cancellation operation cannot move to this uncertainty state.");
            }
            var uncertain =
                new TransportCancellationOperation(
                    operation.id(),
                    operation.transportRequestId(),
                    operation.reasonCode(),
                    state,
                    Optional.empty(),
                    operation.initiatedAt(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    operation.version() + 1);
            auditMutation(
                context,
                uncertain,
                "TRANSPORT_CANCELLATION_" + state.name(),
                Optional.of(reasonCode),
                now);
            cancellations.completeReceipt(key, operation.id(), now);
            return result(uncertain, request, false);
          });
    } catch (CannotAcquireLockException busy) {
      throw conflict(PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  /** Returns only the latest outcome for a linked Transfer's configured worker identity. */
  public Optional<OutcomeSnapshot> getOutcome(
      ExecutionContext context, InternalId transferId, InternalId requestId) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(transferId, "Transfer id");
    requireScope(context, requestId);
    requireConfiguredWorker(context);
    requireAuthority(context, requestId);
    try {
      TransportRequest request =
          requests.find(requestId).orElseThrow(TransportCancellationCommands::notFound);
      if (!request.transferId().equals(transferId)
          || context.scope().facility().filter(request.originFacility()::equals).isEmpty()) {
        throw notFound();
      }
      return cancellations
          .findLatestForRequest(requestId)
          .map(
              operation ->
                  new OutcomeSnapshot(
                      transferId,
                      requestId,
                      operation.id(),
                      operation.state(),
                      operation.outcome(),
                      operation.resolutionEventId(),
                      operation.sourceNamespace(),
                      operation.sourceObservationId(),
                      operation.sourceOccurredAt(),
                      operation.resolvedAt(),
                      operation
                          .resolutionCorrelationId()
                          .map(org.ayuvahak.platform.core.CorrelationId::new)
                          .orElse(request.correlationId())));
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  public CancellationResult resolve(
      ExecutionContext context,
      InternalId operationId,
      ResolutionInput input,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(input, "resolution input");
    requireScope(context, operationId);
    requireAuthority(context, operationId);
    validateSource(input.sourceNamespace(), input.sourceObservationId());
    Instant requestedAt = Objects.requireNonNull(input.occurredAt(), "source occurrence time");
    if (requestedAt.isAfter(clock.instant().plus(FUTURE_SKEW))) {
      throw conflict(
          PlatformErrorCode.TRANSPORT_EVIDENCE_STALE,
          "Transport rejected cancellation outcome evidence with a future source time.");
    }
    CommandReceiptKey key =
        commandKey(context, "transport.cancellation.resolve", operationId, idempotencyKey);
    try {
      return transaction.execute(
          ignored -> {
            Instant now = clock.instant();
            requests.configureBoundedReceiptWait();
            TransportCancellationStore.ReceiptClaim claim =
                cancellations.claimReceipt(key, fingerprint, now);
            if (claim.state() == TransportCancellationStore.ClaimState.KEY_REUSED) {
              throw conflict(
                  PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                  "The Idempotency-Key was already used for different cancellation outcome evidence.");
            }
            if (claim.state() == TransportCancellationStore.ClaimState.IN_PROGRESS) {
              throw conflict(PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
            }
            if (claim.state() == TransportCancellationStore.ClaimState.REPLAY) {
              TransportCancellationOperation replay =
                  cancellations
                      .find(claim.operationId().orElseThrow())
                      .orElseThrow(TransportCancellationCommands::unavailable);
              return result(
                  replay,
                  requests
                      .find(replay.transportRequestId())
                      .orElseThrow(TransportCancellationCommands::notFound),
                  true);
            }
            TransportCancellationOperation operation =
                cancellations
                    .lock(operationId)
                    .orElseThrow(TransportCancellationCommands::notFound);
            TransportRequest request = lockRequest(context, operation.transportRequestId());
            if (operation.state() == TransportCancellationOperation.State.RESOLVED) {
              if (operation.sourceNamespace().filter(input.sourceNamespace()::equals).isEmpty()
                  || operation
                      .sourceObservationId()
                      .filter(input.sourceObservationId()::equals)
                      .isEmpty()
                  || operation
                      .resolutionFingerprint()
                      .filter(fingerprint.value()::equals)
                      .isEmpty()) {
                throw conflict(
                    PlatformErrorCode.TRANSPORT_EVIDENCE_CONFLICT,
                    "The cancellation operation already has a different source outcome.");
              }
              cancellations.completeReceipt(key, operation.id(), now);
              return result(operation, request, true);
            }
            TransportCancellationOperation.Outcome outcome =
                movementStarted(request)
                    ? TransportCancellationOperation.Outcome.MOVEMENT_ALREADY_STARTED
                    : input.outcome();
            if (outcome == TransportCancellationOperation.Outcome.CANCELLED_BEFORE_MOVEMENT
                && request.status() != TransportRequest.Status.CANCELLED) {
              if (!evidence.updateStatus(
                  request.id(),
                  request.version(),
                  TransportRequest.Status.CANCELLED,
                  request.version() + 1,
                  now)) {
                throw conflict(
                    PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
                    "Transport request changed while confirming cancellation.");
              }
              request =
                  new TransportRequest(
                      request.id(),
                      request.transferId(),
                      request.originFacility(),
                      request.destinationFacility(),
                      request.initiatedBy(),
                      request.recordedBy(),
                      TransportRequest.Status.CANCELLED,
                      request.requestedAt(),
                      request.correlationId(),
                      request.version() + 1);
            }
            InternalId resolutionEventId = InternalId.generate();
            if (!cancellations.resolve(
                operation.id(),
                operation.version(),
                outcome,
                now,
                input.sourceNamespace(),
                input.sourceObservationId(),
                fingerprint.value(),
                input.occurredAt(),
                context.correlationId().value(),
                resolutionEventId)) {
              throw conflict(
                  PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
                  "The cancellation outcome changed while it was being reconciled.");
            }
            var resolved =
                new TransportCancellationOperation(
                    operation.id(),
                    operation.transportRequestId(),
                    operation.reasonCode(),
                    TransportCancellationOperation.State.RESOLVED,
                    Optional.of(outcome),
                    operation.initiatedAt(),
                    Optional.of(now),
                    Optional.of(input.sourceNamespace()),
                    Optional.of(input.sourceObservationId()),
                    Optional.of(fingerprint.value()),
                    Optional.of(input.occurredAt()),
                    Optional.of(context.correlationId().value()),
                    Optional.of(resolutionEventId),
                    operation.version() + 1);
            auditMutation(context, resolved, "TRANSPORT_CANCELLATION_RESOLVED", now);
            recordResolvedEvent(
                context,
                request,
                resolved,
                input.sourceNamespace(),
                input.sourceObservationId(),
                input.occurredAt());
            cancellations.completeReceipt(key, operation.id(), now);
            return result(resolved, request, false);
          });
    } catch (CannotAcquireLockException busy) {
      throw conflict(PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DuplicateKeyException duplicate) {
      throw conflict(
          PlatformErrorCode.TRANSPORT_EVIDENCE_CONFLICT,
          "The cancellation outcome source identity conflicts with recorded evidence.");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private TransportRequest lockRequest(ExecutionContext context, InternalId requestId) {
    TransportRequest request =
        requests.lock(requestId).orElseThrow(TransportCancellationCommands::notFound);
    if (context.scope().facility().filter(request.originFacility()::equals).isEmpty()
        || context.scope().organization().isEmpty()) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transport cancellation must use the request's validated origin Facility scope.");
    }
    return request;
  }

  private void requireScope(ExecutionContext context, InternalId target) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(target, "Transport identifier");
    if (context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().facility().isEmpty()
        || context.scope().organization().isEmpty()) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transport cancellation requires explicit Facility execution scope.");
    }
  }

  private void requireAuthority(ExecutionContext context, InternalId target) {
    AuthorizationDecision decision =
        authorization.evaluate(context, TransportAuthorities.cancellation().code());
    if (decision.isAllowed()) return;
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSPORT_CANCELLATION_DENIED",
              "TransportCancellationOperation",
              Optional.of(target),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
              authenticationEvidence(context),
              AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transport withheld cancellation because its denial audit is unavailable.");
    }
    throw new PlatformProblemException(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transport authorization is temporarily unavailable."
            : "The actor is not authorized to cancel Transport at this scope.");
  }

  private void requireConfiguredWorker(ExecutionContext context) {
    boolean configuredWorker =
        context.actor().kind() == ActorKind.SERVICE
            && context.authenticationMechanism()
                == AuthenticationMechanism.CONFIGURED_SERVICE_IDENTITY
            && properties.getWorkerClientId().equals(context.actor().identity().value())
            && authenticationProperties
                .issuer()
                .equals(context.actor().identity().issuer().value());
    if (!configuredWorker) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "Cancellation outcomes are available only to the configured Transfer service identity.");
    }
  }

  private void auditMutation(
      ExecutionContext context,
      TransportCancellationOperation operation,
      String action,
      Instant now) {
    auditMutation(context, operation, action, operation.outcome().map(Enum::name), now);
  }

  private void auditMutation(
      ExecutionContext context,
      TransportCancellationOperation operation,
      String action,
      Optional<String> resultCode,
      Instant now) {
    try {
      audit.recordMutation(
          new AuditEvidence(
              AuditActionKind.PROTECTED_MUTATION,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              action,
              "TransportCancellationOperation",
              Optional.of(operation.id()),
              now,
              AuditOutcome.SUCCEEDED,
              resultCode,
              authenticationEvidence(context),
              AuditAuthorizationOutcome.ALLOWED,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(
                      context.scope().kind(),
                      context.scope().organization(),
                      context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transport cancellation and its required audit could not be committed.");
    }
  }

  private void recordResolvedEvent(
      ExecutionContext context,
      TransportRequest request,
      TransportCancellationOperation operation,
      String sourceNamespace,
      String sourceObservationId,
      Instant occurredAt) {
    var payload = mapper.createObjectNode();
    payload.put("transferReference", request.transferId().toString());
    payload.put("transportRequestReference", request.id().toString());
    payload.put("cancellationOperationReference", operation.id().toString());
    payload.put("originFacilityReference", request.originFacility().value());
    payload.put("destinationFacilityReference", request.destinationFacility().value());
    payload.put("outcome", operation.outcome().orElseThrow().name());
    payload.put("sourceNamespace", sourceNamespace);
    payload.put("sourceObservationId", sourceObservationId);
    outbox.record(
        new EventEnvelope(
            operation.resolutionEventId().orElseThrow(),
            RESOLVED,
            "transport",
            new OccurredAt(occurredAt),
            new RecordedAt(clock.instant()),
            context,
            Optional.of(
                new AggregatePosition(
                    "transport-cancellation/" + operation.id(), operation.version())),
            payload));
  }

  private static CancellationResult result(
      TransportCancellationOperation operation, TransportRequest request, boolean replayed) {
    return new CancellationResult(operation, request.status(), replayed);
  }

  private static void requireOperationRequest(
      TransportCancellationOperation operation, InternalId requestId) {
    if (!operation.transportRequestId().equals(requestId)) {
      throw conflict(
          PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
          "The cancellation receipt does not match this Transport request.");
    }
  }

  private boolean movementStarted(TransportRequest request) {
    return switch (request.status()) {
      case EN_ROUTE_TO_DESTINATION, ARRIVED_DESTINATION -> true;
      case INTERRUPTED, COMPLETED -> evidence.hasConfirmedPatientMovement(request.id());
      default -> false;
    };
  }

  private static void validateReason(String reason) {
    if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw new PlatformProblemException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID,
          "A bounded coded cancellation reason is required.");
    }
  }

  private static void validateSource(String namespace, String sourceId) {
    if (namespace == null
        || namespace.length() > 256
        || !namespace.matches("[A-Za-z][A-Za-z0-9+.-]*:[^\\s\\p{Cntrl}]{1,240}")
        || sourceId == null
        || sourceId.isBlank()
        || !sourceId.equals(sourceId.strip())
        || sourceId.length() > 128
        || sourceId.codePoints().anyMatch(Character::isISOControl)) {
      throw new PlatformProblemException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID,
          "A bounded source namespace and cancellation outcome identity are required.");
    }
  }

  private static CommandReceiptKey commandKey(
      ExecutionContext context, String operation, InternalId target, String rawKey) {
    try {
      return CommandReceiptKey.from(context, operation, target.toString(), rawKey);
    } catch (IllegalArgumentException invalid) {
      throw new PlatformProblemException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID,
          "A valid Idempotency-Key is required for this Transport cancellation.");
    }
  }

  private static AuditAuthenticationEvidence authenticationEvidence(ExecutionContext context) {
    return switch (context.authenticationMechanism()) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static PlatformProblemException conflict(PlatformErrorCode code, String detail) {
    return new PlatformProblemException(HttpStatus.CONFLICT, code, detail);
  }

  private static PlatformProblemException notFound() {
    return new PlatformProblemException(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.NOT_FOUND,
        "The Transport cancellation target was not found.");
  }

  private static PlatformProblemException unavailable() {
    return new PlatformProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSPORT_UNAVAILABLE,
        "Transport cancellation could not be recovered safely.");
  }

  public record ResolutionInput(
      TransportCancellationOperation.Outcome outcome,
      String sourceNamespace,
      String sourceObservationId,
      Instant occurredAt) {}

  public record CancellationResult(
      TransportCancellationOperation operation,
      TransportRequest.Status requestStatus,
      boolean commandReplayed) {}

  public record OutcomeSnapshot(
      InternalId transferId,
      InternalId transportRequestId,
      InternalId cancellationOperationId,
      TransportCancellationOperation.State state,
      Optional<TransportCancellationOperation.Outcome> outcome,
      Optional<InternalId> resolutionEventId,
      Optional<String> sourceNamespace,
      Optional<String> sourceObservationId,
      Optional<Instant> sourceOccurredAt,
      Optional<Instant> resolvedAt,
      org.ayuvahak.platform.core.CorrelationId correlationId) {}
}
