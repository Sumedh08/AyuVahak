package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
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
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.domain.CapacityReservationIntent;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Calls the Capacity owner and durably reconciles only its confirmed reservation result. */
@Service
@Lazy
public final class TransferCapacityReservationRecovery {

  private static final Logger logger =
      LoggerFactory.getLogger(TransferCapacityReservationRecovery.class);
  private final TransferStore transfers;
  private final CapacityReservationPort capacity;
  private final ExecutionContextFactory contexts;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final TransferProperties properties;
  private final ActorReference worker;
  private final Clock clock;

  public TransferCapacityReservationRecovery(
      @Lazy TransferStore transfers,
      CapacityReservationPort capacity,
      ExecutionContextFactory contexts,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      TransferProperties properties,
      org.ayuvahak.platform.authentication.AuthenticationProperties authentication,
      @Qualifier("auditClock") Clock clock) {
    this.transfers = Objects.requireNonNull(transfers, "Transfer store");
    this.capacity = Objects.requireNonNull(capacity, "Capacity reservation port");
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.properties = Objects.requireNonNull(properties, "Transfer properties");
    this.worker =
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getReservationWorkerClientId());
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  /**
   * Makes one bounded attempt for an accepted Transfer after the Acceptance transaction commits.
   */
  public void reconcile(InternalId transferId) {
    try {
      transfers.findReservationIntent(transferId).filter(this::isDue).ifPresent(this::attempt);
    } catch (RuntimeException failure) {
      logger.warn(
          "Transfer Capacity reservation remains pending correlationId={} failureType={}",
          CorrelationId.generate(),
          failure.getClass().getSimpleName());
    }
  }

  /**
   * Processes the bounded due batch; a new instance can recover work left by a previous process.
   */
  public int reconcileDue() {
    int processed = 0;
    try {
      for (CapacityReservationIntent intent :
          transfers.findDueReservationIntents(
              clock.instant(), properties.getReservationBatchSize())) {
        attempt(intent);
        processed++;
      }
    } catch (RuntimeException failure) {
      logger.warn(
          "Transfer Capacity reservation recovery batch stopped failureType={}",
          failure.getClass().getSimpleName());
    }
    return processed;
  }

  private void attempt(CapacityReservationIntent intent) {
    if (intent.outcome() == CapacityReservationIntent.Outcome.UNCONFIGURED
        || intent.outcome() == CapacityReservationIntent.Outcome.RESERVED
        || intent.dimensionCode().isEmpty()) {
      return;
    }
    CorrelationId correlation = CorrelationId.generate();
    ExecutionContext context;
    try {
      context =
          contexts.forBackgroundWork(
              worker,
              RequestedExecutionScope.facility(intent.organization(), intent.facility()),
              correlation,
              Optional.empty());
    } catch (RuntimeException deniedScope) {
      logger.warn(
          "Transfer Capacity reservation scope validation deferred correlationId={} failureType={}",
          correlation,
          deniedScope.getClass().getSimpleName());
      return;
    }
    if (!authorizeTransfer(context, intent.operationId())) {
      return;
    }

    int nextAttempt = Math.addExact(intent.attemptCount(), 1);
    CapacityReservationIntent.Outcome outcome;
    Optional<InternalId> reservationId = Optional.empty();
    Optional<String> reasonCode = Optional.empty();
    if (!isUnexpired(intent, clock.instant())) {
      outcome = CapacityReservationIntent.Outcome.UNKNOWN;
      reasonCode = Optional.of("CAPACITY_RESERVATION_EXPIRED_BEFORE_CONFIRMATION");
    } else {
      try {
        CapacityReservationResult result =
            capacity.reserve(
                context,
                new CapacityReservationRequest(
                    intent.operationId(),
                    intent.transferId(),
                    intent.facility(),
                    intent.dimensionCode().orElseThrow(),
                    intent.unit().orElseThrow(),
                    intent.quantity().orElseThrow(),
                    intent.intervalStart().orElseThrow(),
                    intent.intervalEnd().orElseThrow(),
                    intent.expiresAt().orElseThrow()),
                "transfer-capacity-" + intent.operationId());
        if (!result.operationId().equals(intent.operationId())) {
          outcome = CapacityReservationIntent.Outcome.UNKNOWN;
          reasonCode = Optional.of("CAPACITY_OPERATION_REFERENCE_MISMATCH");
        } else {
          outcome = map(result.outcome());
          reservationId = result.reservationId();
          reasonCode = result.reasonCode();
        }
      } catch (IllegalArgumentException invalidRequest) {
        outcome = CapacityReservationIntent.Outcome.INVALID;
        reasonCode = Optional.of("CAPACITY_REQUEST_INVALID");
      } catch (PlatformProblemException failure) {
        outcome =
            retryable(failure)
                ? CapacityReservationIntent.Outcome.UNAVAILABLE
                : CapacityReservationIntent.Outcome.INVALID;
        reasonCode = Optional.of(safeCode(failure));
      } catch (RuntimeException failure) {
        outcome = CapacityReservationIntent.Outcome.UNAVAILABLE;
        reasonCode = Optional.of("CAPACITY_UNAVAILABLE");
      }
    }

    Instant recordedAt = clock.instant();
    if (outcome == CapacityReservationIntent.Outcome.RESERVED && !isUnexpired(intent, recordedAt)) {
      outcome = CapacityReservationIntent.Outcome.UNKNOWN;
      reservationId = Optional.empty();
      reasonCode = Optional.of("CAPACITY_RESERVATION_EXPIRED_BEFORE_CONFIRMATION");
    }
    Instant nextAttemptAt =
        outcome == CapacityReservationIntent.Outcome.UNAVAILABLE
            ? recordedAt.plus(properties.getReservationPollInterval())
            : recordedAt;
    try {
      CapacityReservationIntent.Outcome finalOutcome = outcome;
      Optional<InternalId> finalReservationId = reservationId;
      Optional<String> finalReasonCode = reasonCode;
      boolean committed =
          transaction.execute(
              status -> {
                boolean changed =
                    transfers.recordReservationOutcome(
                        intent.transferId(),
                        intent.attemptCount(),
                        nextAttempt,
                        finalOutcome,
                        finalReservationId,
                        finalReasonCode,
                        nextAttemptAt,
                        recordedAt);
                if (changed) {
                  auditMutation(context, intent.operationId(), finalOutcome, finalReasonCode);
                }
                return changed;
              });
      if (Boolean.TRUE.equals(committed)) {
        logger.info(
            "Transfer Capacity reservation outcome recorded correlationId={} outcome={}",
            correlation,
            finalOutcome);
      }
    } catch (RuntimeException failure) {
      logger.warn(
          "Transfer Capacity reservation outcome remains recoverable correlationId={} failureType={}",
          correlation,
          failure.getClass().getSimpleName());
    }
  }

  private boolean authorizeTransfer(ExecutionContext context, InternalId operationId) {
    var decision =
        authorization
            .getObject()
            .evaluate(context, TransferAuthorities.reservationReconciliation().code());
    if (decision.isAllowed()) {
      return true;
    }
    boolean unavailable =
        decision.denialReason().orElse(null)
            == org.ayuvahak.platform.authorization.domain.AuthorizationDecision.DenialReason
                .GRANT_STORE_UNAVAILABLE;
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  "TRANSFER_RESERVATION_RECONCILIATION_DENIED",
                  "CapacityReservationOperation",
                  Optional.of(operationId),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of(unavailable ? "AUTHORIZATION_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
                  AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId(),
                  Optional.of(targetContext(context))));
    } catch (AuditPersistenceUnavailableException auditFailure) {
      logger.error(
          "Transfer reservation authority denial audit unavailable correlationId={}",
          context.correlationId());
    }
    logger.warn(
        "Transfer Capacity reservation reconciliation denied correlationId={} code={}",
        context.correlationId(),
        unavailable ? "AUTHORIZATION_UNAVAILABLE" : "AUTHORIZATION_DENIED");
    return false;
  }

  private void auditMutation(
      ExecutionContext context,
      InternalId operationId,
      CapacityReservationIntent.Outcome outcome,
      Optional<String> reasonCode) {
    auditWriter
        .getObject()
        .recordMutation(
            new AuditEvidence(
                AuditActionKind.PROTECTED_MUTATION,
                Optional.of(context.actor()),
                Optional.of(context.scope()),
                "TRANSFER_CAPACITY_RESERVATION_RECONCILE",
                "CapacityReservationOperation",
                Optional.of(operationId),
                clock.instant(),
                outcome == CapacityReservationIntent.Outcome.RESERVED
                    ? AuditOutcome.SUCCEEDED
                    : AuditOutcome.FAILED,
                reasonCode,
                AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId(),
                Optional.of(targetContext(context))));
  }

  private static AuditTargetContext targetContext(ExecutionContext context) {
    return new AuditTargetContext(
        context.scope().kind(), context.scope().organization(), context.scope().facility());
  }

  private boolean isDue(CapacityReservationIntent intent) {
    return (intent.outcome() == CapacityReservationIntent.Outcome.PENDING
            || intent.outcome() == CapacityReservationIntent.Outcome.UNAVAILABLE)
        && intent.nextAttemptAt().compareTo(clock.instant()) <= 0;
  }

  private static boolean isUnexpired(CapacityReservationIntent intent, Instant at) {
    return intent.expiresAt().filter(expiresAt -> expiresAt.isAfter(at)).isPresent();
  }

  private static CapacityReservationIntent.Outcome map(CapacityReservationResult.Outcome outcome) {
    return switch (outcome) {
      case RESERVED -> CapacityReservationIntent.Outcome.RESERVED;
      case CONFLICT -> CapacityReservationIntent.Outcome.CONFLICT;
      case UNKNOWN -> CapacityReservationIntent.Outcome.UNKNOWN;
      case INVALID -> CapacityReservationIntent.Outcome.INVALID;
    };
  }

  private static String safeCode(PlatformProblemException failure) {
    String code = failure.code().name();
    return code.matches("[A-Z][A-Z0-9_]{0,79}") ? code : "CAPACITY_UNAVAILABLE";
  }

  private static boolean retryable(PlatformProblemException failure) {
    return failure.code() == org.ayuvahak.platform.api.PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS
        || failure.status().is5xxServerError();
  }
}
