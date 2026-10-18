package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Drives already-persisted origin operations; remote uncertainty never changes owner truth. */
@Service
@Lazy
public final class FederatedReceivingOriginOperationCoordinator {

  private static final EventType ATTEMPT_CHANGED =
      EventType.parse("transfer.federated-origin-receiving-attempt-changed.v1");

  // Phase 9 caps a partner operation at five minutes. Do not race an in-flight request with an
  // early status lookup that can observe the receiver before its owner transaction commits.
  private static final java.time.Duration IN_FLIGHT_GRACE = java.time.Duration.ofSeconds(310);

  private final FederatedOriginReceivingAttemptStore store;
  private final TransferStore transfers;
  private final FederatedReceivingOriginExchange exchange;
  private final AuditWriter audit;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public FederatedReceivingOriginOperationCoordinator(
      @Lazy FederatedOriginReceivingAttemptStore store,
      @Lazy TransferStore transfers,
      FederatedReceivingOriginExchange exchange,
      AuditWriter audit,
      OutboxWriter outbox,
      ObjectMapper mapper,
      @Lazy PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "origin receiving attempt store");
    this.transfers = Objects.requireNonNull(transfers, "Transfer store");
    this.exchange = Objects.requireNonNull(exchange, "origin receiving exchange");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.outbox = Objects.requireNonNull(outbox, "outbox writer");
    this.mapper = Objects.requireNonNull(mapper, "object mapper");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  FederatedOriginReceivingAttempt submit(ExecutionContext context, InternalId operationId) {
    Objects.requireNonNull(context, "execution context");
    PreparedOperation prepared =
        Objects.requireNonNull(
            transaction.execute(
                ignored -> {
                  FederatedOriginReceivingAttempt current = require(operationId);
                  if (current.state() == FederatedOriginReceivingAttempt.State.PENDING) {
                    FederatedOriginReceivingAttempt sent =
                        current.withState(
                            FederatedOriginReceivingAttempt.State.SENT, clock.instant());
                    if (!store.update(sent, current.version())) {
                      throw unavailable();
                    }
                    recordOwnerChange(context, sent);
                    return new PreparedOperation(sent, false, false);
                  }
                  if (current.state() == FederatedOriginReceivingAttempt.State.SENT
                      && clock.instant().isBefore(current.updatedAt().plus(IN_FLIGHT_GRACE))) {
                    return new PreparedOperation(current, false, true);
                  }
                  boolean statusOnly =
                      current.state() == FederatedOriginReceivingAttempt.State.SENT
                          || current.state()
                              == FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN
                          || current.state()
                              == FederatedOriginReceivingAttempt.State.RECONCILIATION_REQUIRED;
                  boolean terminal =
                      current.state() == FederatedOriginReceivingAttempt.State.RESOLVED
                          || current.state() == FederatedOriginReceivingAttempt.State.REJECTED;
                  return new PreparedOperation(current, statusOnly, terminal);
                }),
            "prepared origin operation");
    if (prepared.skipExchange()) {
      return prepared.attempt();
    }
    try {
      FederatedReceivingOriginExchangeResult result =
          exchange.execute(prepared.attempt(), prepared.statusOnly());
      return recordResult(context, prepared.attempt(), result);
    } catch (FederatedReceivingOriginExchangeException uncertain) {
      FederatedOriginReceivingAttempt.State nextState =
          switch (uncertain.disposition()) {
            case NOT_SENT -> FederatedOriginReceivingAttempt.State.PENDING;
            case REJECTED -> FederatedOriginReceivingAttempt.State.REJECTED;
            case OUTCOME_UNKNOWN -> FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN;
            case INVALID_RESPONSE -> FederatedOriginReceivingAttempt.State.RECONCILIATION_REQUIRED;
          };
      return update(context, prepared.attempt(), nextState);
    }
  }

  FederatedOriginReceivingAttempt reconcile(ExecutionContext context, InternalId operationId) {
    Objects.requireNonNull(context, "execution context");
    FederatedOriginReceivingAttempt current =
        store.lockByOperationId(operationId).orElseThrow(this::unavailable);
    if (current.state() == FederatedOriginReceivingAttempt.State.PENDING) {
      return submit(context, operationId);
    }
    if (current.state() != FederatedOriginReceivingAttempt.State.SENT
        && current.state() != FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN
        && current.state() != FederatedOriginReceivingAttempt.State.RECONCILIATION_REQUIRED) {
      return current;
    }
    try {
      return recordResult(context, current, exchange.execute(current, true));
    } catch (FederatedReceivingOriginExchangeException failure) {
      FederatedOriginReceivingAttempt.State nextState =
          failure.disposition() == FederatedReceivingOriginExchangeException.Disposition.REJECTED
              ? FederatedOriginReceivingAttempt.State.REJECTED
              : failure.disposition()
                      == FederatedReceivingOriginExchangeException.Disposition.INVALID_RESPONSE
                  ? FederatedOriginReceivingAttempt.State.RECONCILIATION_REQUIRED
                  : FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN;
      return update(context, current, nextState);
    }
  }

  private FederatedOriginReceivingAttempt recordResult(
      ExecutionContext context,
      FederatedOriginReceivingAttempt current,
      FederatedReceivingOriginExchangeResult result) {
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              FederatedOriginReceivingAttempt locked = require(current.operationId());
              if (locked.version() != current.version()) {
                throw unavailable();
              }
              FederatedOriginReceivingAttempt updated =
                  locked.withRemoteResult(
                      result.outcome(),
                      result.reservationStatus(),
                      result.receivingRequestId(),
                      result.capacityReservationId(),
                      result.clinicalAssessment(),
                      result.operationalAssessment(),
                      result.acceptedAt(),
                      result.sourceUpdatedAt(),
                      result.receivingVersion(),
                      clock.instant());
              if (updated == locked) {
                return locked;
              }
              if (!store.update(updated, locked.version())) {
                throw unavailable();
              }
              recordOwnerChange(context, updated);
              return updated;
            }),
        "origin operation result");
  }

  private FederatedOriginReceivingAttempt update(
      ExecutionContext context,
      FederatedOriginReceivingAttempt current,
      FederatedOriginReceivingAttempt.State nextState) {
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              FederatedOriginReceivingAttempt locked = require(current.operationId());
              if (locked.version() != current.version()) {
                return locked;
              }
              if (locked.state() == nextState) {
                return locked;
              }
              FederatedOriginReceivingAttempt updated =
                  locked.withState(nextState, clock.instant());
              if (!store.update(updated, locked.version())) {
                throw unavailable();
              }
              recordOwnerChange(context, updated);
              return updated;
            }),
        "origin operation state");
  }

  private void recordOwnerChange(
      ExecutionContext context, FederatedOriginReceivingAttempt attempt) {
    Instant now = attempt.updatedAt();
    var journey = transfers.lockJourney(attempt.journeyId()).orElseThrow(this::unavailable);
    long nextJourneyVersion = Math.addExact(journey.version(), 1);
    if (!transfers.advanceJourneyVersion(journey.id(), journey.version())) {
      throw unavailable();
    }
    audit.recordMutation(
        new AuditEvidence(
            AuditActionKind.PROTECTED_MUTATION,
            Optional.of(context.actor()),
            Optional.of(context.scope()),
            "TRANSFER_FEDERATED_RECEIVING_OPERATION_UPDATE",
            "FederatedOriginReceivingAttempt",
            Optional.of(attempt.id()),
            now,
            AuditOutcome.SUCCEEDED,
            Optional.of("FEDERATION_STATE_" + attempt.state().name()),
            authenticationEvidence(context.authenticationMechanism()),
            AuditAuthorizationOutcome.ALLOWED,
            context.correlationId()));
    var payload = mapper.createObjectNode();
    payload.put("resourceType", "transfer-journey");
    payload.put("resourceId", attempt.journeyId().toString());
    payload.put("version", nextJourneyVersion);
    payload.put("operationState", attempt.state().name());
    outbox.record(
        new EventEnvelope(
            InternalId.generate(),
            ATTEMPT_CHANGED,
            "transfer",
            new OccurredAt(now),
            new RecordedAt(now),
            context,
            Optional.of(
                new AggregatePosition(
                    "transfer-journey/" + attempt.journeyId(), nextJourneyVersion)),
            payload));
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

  private FederatedOriginReceivingAttempt require(InternalId operationId) {
    return store.lockByOperationId(operationId).orElseThrow(this::unavailable);
  }

  private org.springframework.web.server.ResponseStatusException unavailable() {
    return new org.springframework.web.server.ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "The federated receiving operation is unavailable.");
  }

  private record PreparedOperation(
      FederatedOriginReceivingAttempt attempt, boolean statusOnly, boolean skipExchange) {}
}
