package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityReservationOutcome;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.capacity.domain.FederatedReceivingCapacityReservationRequest;
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
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Resolves only a receiver-local typed Capacity reservation by its pre-persisted operation ID. */
@Service
@Lazy
public final class FederatedReceivingCapacityRecovery {

  private static final Logger logger =
      LoggerFactory.getLogger(FederatedReceivingCapacityRecovery.class);
  private static final String UNKNOWN_REASON = "CAPACITY_OUTCOME_UNCONFIRMED";
  private static final EventType RECEIVING_REQUEST_CHANGED =
      EventType.parse("transfer.federated-receiving-request-changed.v1");

  private final FederatedReceivingRequestStore store;
  private final FederatedReceivingCapacityPort capacity;
  private final ExecutionContextFactory contexts;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final OutboxWriter outboxWriter;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transaction;
  private final String workerClientId;
  private final ActorReference worker;
  private final Clock clock;

  public FederatedReceivingCapacityRecovery(
      @Lazy FederatedReceivingRequestStore store,
      FederatedReceivingCapacityPort capacity,
      ExecutionContextFactory contexts,
      AuthorizationService authorization,
      AuditWriter audit,
      OutboxWriter outboxWriter,
      ObjectMapper objectMapper,
      @Lazy PlatformTransactionManager transactionManager,
      org.ayuvahak.transfer.application.TransferSettings settings,
      org.ayuvahak.platform.authentication.AuthenticationProperties authentication,
      @Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "federated receiving store");
    this.capacity = Objects.requireNonNull(capacity, "Capacity port");
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.outboxWriter = Objects.requireNonNull(outboxWriter, "outbox writer");
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.workerClientId =
        Objects.requireNonNull(settings, "Transfer settings").getReservationWorkerClientId();
    this.worker =
        ActorReference.service(
            new IssuerId(
                Objects.requireNonNull(authentication, "authentication settings").issuer()),
            workerClientId);
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  /** Attempts or reconciles the same persisted Capacity operation; never creates a new identity. */
  public FederatedReceivingRequest reconcile(InternalId receivingRequestId) {
    Objects.requireNonNull(receivingRequestId, "receiving request id");
    FederatedReceivingRequest current =
        store.find(receivingRequestId).orElseThrow(FederatedReceivingCapacityRecovery::notFound);
    if (current.capacityOperationId().isEmpty()
        || current.acceptedAt().isEmpty()
        || !isRecoverable(current.reservationStatus())) {
      return current;
    }
    ExecutionContext context = serviceContext(current);
    if (!authorizeTransfer(context, current.id())) {
      return recordUnknown(current.id(), "TRANSFER_RECONCILIATION_DENIED");
    }

    FederatedReceivingRequest.ReservationStatus resolved;
    Optional<InternalId> reservationId = Optional.empty();
    Optional<String> reason = Optional.empty();
    long lifecycleSequence = current.capacityLifecycleSequence();
    try {
      InternalId operationId = current.capacityOperationId().orElseThrow();
      var observed = capacity.currentOutcome(context, operationId, current.id());
      if (!observed.operationId().equals(operationId)
          || !observed.receivingRequestId().equals(current.id())) {
        throw new IllegalStateException("Capacity returned a different typed operation subject");
      }
      if (observed.status() != CapacityReservationOutcome.Status.UNKNOWN) {
        resolved = map(observed.status());
        reservationId = observed.reservationId();
        reason = observed.reasonCode();
        lifecycleSequence = observed.sequence();
      } else {
        CapacityReservationResult result =
            capacity.reserve(
                context, capacityRequest(current), capacityIdempotencyKey(operationId));
        if (!result.operationId().equals(operationId)) {
          throw new IllegalStateException("Capacity returned a different operation identity");
        }
        reason = result.reasonCode();
        switch (result.outcome()) {
          case RESERVED -> {
            var confirmed = capacity.currentOutcome(context, operationId, current.id());
            if (!confirmed.operationId().equals(operationId)
                || !confirmed.receivingRequestId().equals(current.id())
                || confirmed.status() == CapacityReservationOutcome.Status.UNKNOWN) {
              throw new IllegalStateException("Capacity reservation result is not recoverable yet");
            }
            resolved = map(confirmed.status());
            reservationId = confirmed.reservationId();
            reason = confirmed.reasonCode();
            lifecycleSequence = confirmed.sequence();
          }
          case CONFLICT -> resolved = FederatedReceivingRequest.ReservationStatus.CONFLICT;
          case INVALID -> resolved = FederatedReceivingRequest.ReservationStatus.INVALID;
          case UNKNOWN -> resolved = FederatedReceivingRequest.ReservationStatus.UNKNOWN;
          default -> throw new IllegalStateException("Unsupported Capacity result");
        }
      }
    } catch (RuntimeException uncertain) {
      logger.warn(
          "Federated receiving Capacity outcome remains unknown requestId={} failureType={}",
          receivingRequestId,
          uncertain.getClass().getSimpleName());
      return recordUnknown(receivingRequestId, UNKNOWN_REASON);
    }
    return recordOutcome(receivingRequestId, resolved, reservationId, reason, lifecycleSequence);
  }

  private FederatedReceivingRequest recordUnknown(InternalId requestId, String reason) {
    return recordOutcome(
        requestId,
        FederatedReceivingRequest.ReservationStatus.OUTCOME_UNKNOWN,
        Optional.empty(),
        Optional.of(reason),
        0);
  }

  private FederatedReceivingRequest recordOutcome(
      InternalId requestId,
      FederatedReceivingRequest.ReservationStatus outcome,
      Optional<InternalId> reservationId,
      Optional<String> reasonCode,
      long lifecycleSequence) {
    Instant now = clock.instant();
    try {
      return Objects.requireNonNull(
          transaction.execute(
              ignored -> {
                FederatedReceivingRequest locked =
                    store.lock(requestId).orElseThrow(FederatedReceivingCapacityRecovery::notFound);
                if (locked.capacityOperationId().isEmpty()
                    || !isRecoverable(locked.reservationStatus())) {
                  return locked;
                }
                if (lifecycleSequence < locked.capacityLifecycleSequence()) {
                  return locked;
                }
                boolean same =
                    locked.reservationStatus() == outcome
                        && locked.capacityReservationId().equals(reservationId)
                        && locked.capacityReasonCode().equals(reasonCode)
                        && (lifecycleSequence == 0
                            || lifecycleSequence == locked.capacityLifecycleSequence());
                if (same) {
                  return locked;
                }
                FederatedReceivingRequest updated =
                    locked.withCapacityOutcome(
                        outcome,
                        reservationId,
                        reasonCode,
                        Math.max(locked.capacityLifecycleSequence(), lifecycleSequence),
                        now);
                ExecutionContext changeContext = serviceContext(locked);
                if (!store.update(updated, locked.version())) {
                  throw unavailable();
                }
                audit.recordMutation(
                    new AuditEvidence(
                        AuditActionKind.PROTECTED_MUTATION,
                        Optional.of(worker),
                        Optional.empty(),
                        "TRANSFER_FEDERATED_CAPACITY_RECONCILE",
                        "FederatedReceivingRequest",
                        Optional.of(requestId),
                        now,
                        outcome == FederatedReceivingRequest.ReservationStatus.RESERVED
                            ? AuditOutcome.SUCCEEDED
                            : AuditOutcome.FAILED,
                        reasonCode,
                        AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                        AuditAuthorizationOutcome.ALLOWED,
                        changeContext.correlationId()));
                recordChanged(changeContext, updated, now, now);
                return updated;
              }),
          "federated Capacity reconciliation result");
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Capacity outcome and its required audit evidence could not be committed.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSFER_UNAVAILABLE,
          "The Capacity outcome could not be recorded safely.");
    }
  }

  private boolean authorizeTransfer(ExecutionContext context, InternalId requestId) {
    AuthorizationDecision decision =
        authorization.evaluate(context, TransferAuthorities.reservationReconciliation().code());
    if (decision.isAllowed()) {
      return true;
    }
    audit.recordNonMutating(
        new AuditEvidence(
            AuditActionKind.SECURITY_OUTCOME,
            Optional.of(worker),
            Optional.of(context.scope()),
            "TRANSFER_FEDERATED_CAPACITY_RECONCILE_DENIED",
            "FederatedReceivingRequest",
            Optional.of(requestId),
            clock.instant(),
            AuditOutcome.DENIED,
            Optional.of(
                decision.denialReason().orElseThrow()
                        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
                    ? "AUTHORIZATION_STORE_UNAVAILABLE"
                    : "AUTHORIZATION_DENIED"),
            AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
            AuditAuthorizationOutcome.DENIED,
            context.correlationId()));
    return false;
  }

  private ExecutionContext serviceContext(FederatedReceivingRequest request) {
    return contexts.forBackgroundWork(
        worker,
        RequestedExecutionScope.facility(
            request.destinationOrganization(), request.destinationFacility()),
        CorrelationId.generate(),
        Optional.empty());
  }

  private void recordChanged(
      ExecutionContext context,
      FederatedReceivingRequest request,
      Instant occurredAt,
      Instant recordedAt) {
    var payload = objectMapper.createObjectNode();
    payload.put("receivingRequestReference", request.id().toString());
    payload.put("requestVersion", request.version());
    payload.put("requestState", request.status().name());
    payload.put("capacityReservationStatus", request.reservationStatus().name());
    outboxWriter.record(
        new EventEnvelope(
            InternalId.generate(),
            RECEIVING_REQUEST_CHANGED,
            "transfer",
            new OccurredAt(occurredAt),
            new RecordedAt(recordedAt),
            context,
            Optional.of(
                new AggregatePosition(
                    "federated-receiving-request/" + request.id(), request.version())),
            payload));
  }

  private static FederatedReceivingCapacityReservationRequest capacityRequest(
      FederatedReceivingRequest request) {
    InternalId operationId = request.capacityOperationId().orElseThrow();
    return new FederatedReceivingCapacityReservationRequest(
        operationId,
        request.id(),
        request.destinationFacility(),
        request.capacityDimensionCode(),
        request.capacityUnit(),
        request.capacityQuantity(),
        request.capacityIntervalStart(),
        request.capacityIntervalEnd(),
        request.capacityIntervalEnd());
  }

  private static String capacityIdempotencyKey(InternalId operationId) {
    return "federated-receiving-capacity-" + operationId;
  }

  private static FederatedReceivingRequest.ReservationStatus map(
      CapacityReservationOutcome.Status status) {
    return switch (status) {
      case RESERVED -> FederatedReceivingRequest.ReservationStatus.RESERVED;
      case AT_RISK -> FederatedReceivingRequest.ReservationStatus.AT_RISK;
      case RELEASED -> FederatedReceivingRequest.ReservationStatus.RELEASED;
      case EXPIRED -> FederatedReceivingRequest.ReservationStatus.EXPIRED;
      case UNKNOWN -> FederatedReceivingRequest.ReservationStatus.UNKNOWN;
    };
  }

  private static boolean isRecoverable(FederatedReceivingRequest.ReservationStatus status) {
    return status == FederatedReceivingRequest.ReservationStatus.PENDING
        || status == FederatedReceivingRequest.ReservationStatus.OUTCOME_UNKNOWN
        || status == FederatedReceivingRequest.ReservationStatus.UNAVAILABLE
        || status == FederatedReceivingRequest.ReservationStatus.UNKNOWN;
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.TRANSFER_REQUEST_NOT_FOUND,
        "The federated receiving request was not found.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "The federated receiving Capacity result could not be committed safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
