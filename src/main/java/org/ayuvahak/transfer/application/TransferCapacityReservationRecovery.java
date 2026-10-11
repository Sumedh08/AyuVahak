package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityReleaseResult;
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
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferProgressFact;
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
      for (InternalId transferId :
          transfers.findPendingCancellationTransfers(properties.getReservationBatchSize())) {
        reconcileCancellation(transferId);
        processed++;
      }
    } catch (RuntimeException failure) {
      logger.warn(
          "Transfer Capacity reservation recovery batch stopped failureType={}",
          failure.getClass().getSimpleName());
    }
    return processed;
  }

  /**
   * Releases a Capacity-owned hold only after Transfer has durably entered pending cancellation.
   */
  public void reconcileCancellation(InternalId transferId) {
    try {
      CapacityReservationIntent intent = transfers.findReservationIntent(transferId).orElseThrow();
      if (!hasDefinitiveReservationOutcome(intent.outcome())) {
        return;
      }
      CorrelationId correlation = CorrelationId.generate();
      ExecutionContext context =
          contexts.forBackgroundWork(
              worker,
              RequestedExecutionScope.facility(intent.organization(), intent.facility()),
              correlation,
              Optional.empty());
      if (!authorizeTransfer(context, transferId)) {
        return;
      }
      CapacityReleaseResult release = capacity.release(context, transferId);
      completeCancellationRelease(context, intent, release);
    } catch (RuntimeException failure) {
      logger.warn(
          "Transfer cancellation release remains pending correlationId={} failureType={}",
          CorrelationId.generate(),
          failure.getClass().getSimpleName());
    }
  }

  private void completeCancellationRelease(
      ExecutionContext context, CapacityReservationIntent intent, CapacityReleaseResult release) {
    Instant recordedAt = clock.instant();
    transaction.executeWithoutResult(
        status -> {
          CapacityReservationIntent.Outcome reservationOutcome =
              CapacityReservationIntent.Outcome.valueOf(release.outcome().name());
          if (!transfers.recordReservationLifecycle(
              intent.transferId(),
              release.sequence(),
              reservationOutcome,
              Optional.of("CAPACITY_" + release.outcome().name()))) {
            return;
          }
          TransferProgressTarget target =
              transfers.lockProgressTarget(intent.transferId()).orElse(null);
          if (target == null
              || target.transfer().status()
                  != CoordinatedTransfer.Status.CANCELLATION_PENDING_RELEASE) {
            return;
          }
          CoordinatedTransfer transfer = target.transfer();
          TransferProgressFact fact =
              new TransferProgressFact(
                  InternalId.generate(),
                  transfer.id(),
                  transfer.journeyId(),
                  TransferProgressFact.Kind.CANCELLATION_RELEASE,
                  Optional.of(transfer.status()),
                  CoordinatedTransfer.Status.CANCELLED,
                  Optional.empty(),
                  Optional.of("CAPACITY_" + release.outcome().name()),
                  Optional.empty(),
                  context.actor(),
                  TransferAuthorities.reservationReconciliation().code(),
                  context.scope().organization().orElseThrow(),
                  context.scope().facility().orElseThrow(),
                  recordedAt,
                  recordedAt,
                  Math.addExact(transfer.version(), 1),
                  Math.addExact(target.journeyVersion(), 1));
          if (!transfers.recordProgress(fact)) {
            return;
          }
          auditCancellationRelease(context, transfer.id(), release.outcome());
        });
  }

  private void auditCancellationRelease(
      ExecutionContext context, InternalId transferId, CapacityReleaseResult.Outcome outcome) {
    auditWriter
        .getObject()
        .recordMutation(
            new AuditEvidence(
                AuditActionKind.PROTECTED_MUTATION,
                Optional.of(context.actor()),
                Optional.of(context.scope()),
                "TRANSFER_CANCELLATION_RELEASE_RECONCILE",
                "Transfer",
                Optional.of(transferId),
                clock.instant(),
                AuditOutcome.SUCCEEDED,
                Optional.of("CAPACITY_" + outcome.name()),
                AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId(),
                Optional.of(targetContext(context))));
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
    Instant claimedAt = clock.instant();
    int claimedAttemptCount;
    try {
      claimedAttemptCount = Math.addExact(intent.attemptCount(), 1);
      boolean claimed =
          transaction.execute(
              status ->
                  transfers.claimReservationAttempt(
                      intent.transferId(),
                      intent.attemptCount(),
                      claimedAttemptCount,
                      claimedAt.plus(properties.getReservationAttemptLease()),
                      claimedAt));
      if (!Boolean.TRUE.equals(claimed)) {
        return;
      }
    } catch (RuntimeException failure) {
      logger.warn(
          "Transfer Capacity reservation attempt claim remains recoverable correlationId={} failureType={}",
          correlation,
          failure.getClass().getSimpleName());
      return;
    }

    CapacityReservationIntent.Outcome outcome;
    Optional<InternalId> reservationId = Optional.empty();
    Optional<String> reasonCode = Optional.empty();
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

    Instant recordedAt = clock.instant();
    Instant nextAttemptAt =
        outcome == CapacityReservationIntent.Outcome.UNAVAILABLE
                || outcome == CapacityReservationIntent.Outcome.UNKNOWN
            ? recordedAt.plus(properties.getReservationPollInterval())
            : recordedAt;
    CapacityReservationIntent.Outcome resolvedOutcome = outcome;
    Optional<InternalId> resolvedReservationId = reservationId;
    Optional<String> resolvedReasonCode = reasonCode;
    try {
      Boolean recorded =
          transaction.execute(
              status -> {
                boolean changed =
                    transfers.recordReservationOutcome(
                        intent.transferId(),
                        claimedAttemptCount,
                        resolvedOutcome,
                        resolvedReservationId,
                        resolvedReasonCode,
                        resolvedOutcome == CapacityReservationIntent.Outcome.RESERVED
                            && isUnexpired(intent, recordedAt),
                        nextAttemptAt,
                        recordedAt);
                if (changed) {
                  auditMutation(context, intent.operationId(), resolvedOutcome, resolvedReasonCode);
                }
                return changed;
              });
      if (!Boolean.TRUE.equals(recorded)) {
        return;
      }
      logger.info(
          "Transfer Capacity reservation outcome recorded correlationId={} outcome={}",
          correlation,
          resolvedOutcome);
      if (transfers
              .findProgressTarget(intent.transferId())
              .filter(
                  target ->
                      target.transfer().status()
                          == CoordinatedTransfer.Status.CANCELLATION_PENDING_RELEASE)
              .isPresent()
          && hasDefinitiveReservationOutcome(resolvedOutcome)) {
        reconcileCancellation(intent.transferId());
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
            || intent.outcome() == CapacityReservationIntent.Outcome.IN_PROGRESS
            || intent.outcome() == CapacityReservationIntent.Outcome.UNAVAILABLE
            || intent.outcome() == CapacityReservationIntent.Outcome.UNKNOWN)
        && intent.nextAttemptAt().compareTo(clock.instant()) <= 0;
  }

  private static boolean isUnexpired(CapacityReservationIntent intent, Instant at) {
    return intent.expiresAt().filter(expiresAt -> expiresAt.isAfter(at)).isPresent();
  }

  private static boolean hasDefinitiveReservationOutcome(
      CapacityReservationIntent.Outcome outcome) {
    return outcome != CapacityReservationIntent.Outcome.PENDING
        && outcome != CapacityReservationIntent.Outcome.IN_PROGRESS
        && outcome != CapacityReservationIntent.Outcome.UNAVAILABLE
        && outcome != CapacityReservationIntent.Outcome.UNKNOWN;
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
