package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Drives already-persisted origin operations; remote uncertainty never changes owner truth. */
@Service
@Lazy
public final class FederatedReceivingOriginOperationCoordinator {

  private final FederatedOriginReceivingAttemptStore store;
  private final FederatedReceivingOriginExchange exchange;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public FederatedReceivingOriginOperationCoordinator(
      @Lazy FederatedOriginReceivingAttemptStore store,
      FederatedReceivingOriginExchange exchange,
      @Lazy PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "origin receiving attempt store");
    this.exchange = Objects.requireNonNull(exchange, "origin receiving exchange");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  FederatedOriginReceivingAttempt submit(InternalId operationId) {
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
                    return new PreparedOperation(sent, false, false);
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
      return recordResult(prepared.attempt(), result);
    } catch (FederatedReceivingOriginExchangeException uncertain) {
      FederatedOriginReceivingAttempt.State nextState =
          switch (uncertain.disposition()) {
            case NOT_SENT -> FederatedOriginReceivingAttempt.State.PENDING;
            case REJECTED -> FederatedOriginReceivingAttempt.State.REJECTED;
            case OUTCOME_UNKNOWN -> FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN;
            case INVALID_RESPONSE -> FederatedOriginReceivingAttempt.State.RECONCILIATION_REQUIRED;
          };
      return update(prepared.attempt(), nextState);
    }
  }

  FederatedOriginReceivingAttempt reconcile(InternalId operationId) {
    FederatedOriginReceivingAttempt current =
        store.lockByOperationId(operationId).orElseThrow(this::unavailable);
    if (current.state() == FederatedOriginReceivingAttempt.State.PENDING) {
      return submit(operationId);
    }
    if (current.state() != FederatedOriginReceivingAttempt.State.SENT
        && current.state() != FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN
        && current.state() != FederatedOriginReceivingAttempt.State.RECONCILIATION_REQUIRED) {
      return current;
    }
    try {
      return recordResult(current, exchange.execute(current, true));
    } catch (FederatedReceivingOriginExchangeException failure) {
      FederatedOriginReceivingAttempt.State nextState =
          failure.disposition() == FederatedReceivingOriginExchangeException.Disposition.REJECTED
              ? FederatedOriginReceivingAttempt.State.REJECTED
              : failure.disposition()
                      == FederatedReceivingOriginExchangeException.Disposition.INVALID_RESPONSE
                  ? FederatedOriginReceivingAttempt.State.RECONCILIATION_REQUIRED
                  : FederatedOriginReceivingAttempt.State.OUTCOME_UNKNOWN;
      return update(current, nextState);
    }
  }

  private FederatedOriginReceivingAttempt recordResult(
      FederatedOriginReceivingAttempt current, FederatedReceivingOriginExchangeResult result) {
    FederatedOriginReceivingAttempt updated =
        current.withRemoteResult(
            result.outcome(),
            result.receivingRequestId(),
            result.capacityReservationId(),
            result.receivingVersion(),
            clock.instant());
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              FederatedOriginReceivingAttempt locked = require(current.operationId());
              if (locked.version() != current.version()) {
                throw unavailable();
              }
              if (!store.update(updated, locked.version())) {
                throw unavailable();
              }
              return updated;
            }),
        "origin operation result");
  }

  private FederatedOriginReceivingAttempt update(
      FederatedOriginReceivingAttempt current, FederatedOriginReceivingAttempt.State nextState) {
    return Objects.requireNonNull(
        transaction.execute(
            ignored -> {
              FederatedOriginReceivingAttempt locked = require(current.operationId());
              if (locked.version() != current.version()) {
                return locked;
              }
              FederatedOriginReceivingAttempt updated =
                  locked.withState(nextState, clock.instant());
              if (!store.update(updated, locked.version())) {
                throw unavailable();
              }
              return updated;
            }),
        "origin operation state");
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
