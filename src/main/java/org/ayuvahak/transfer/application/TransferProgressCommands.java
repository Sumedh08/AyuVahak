package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
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
import org.ayuvahak.platform.core.ExternalReference;
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
import org.ayuvahak.transfer.domain.CapacityReservationIntent;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferProgressCorrection;
import org.ayuvahak.transfer.domain.TransferProgressFact;
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
import tools.jackson.databind.ObjectMapper;

/** Attributable Transfer progress commands and Transfer-owned chronology. */
@Service
@Lazy
public final class TransferProgressCommands {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String COMMAND_BUSY_DETAIL =
      "A matching Transfer progress command is still being processed; retry with the same idempotency key.";

  private final TransferStore store;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final OutboxWriter outboxWriter;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<TransferCapacityReservationRecovery> reservationRecovery;

  public TransferProgressCommands(
      @Lazy TransferStore store,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock,
      OutboxWriter outboxWriter,
      ObjectMapper objectMapper,
      ObjectProvider<TransferCapacityReservationRecovery> reservationRecovery) {
    this.store = Objects.requireNonNull(store, "Transfer store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
    this.outboxWriter = Objects.requireNonNull(outboxWriter, "outbox writer");
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
    this.reservationRecovery = Objects.requireNonNull(reservationRecovery, "reservation verifier");
  }

  public TransferProgressCommandResult record(
      ExecutionContext context,
      InternalId transferId,
      TransferProgressFact.Kind kind,
      Instant occurredAt,
      Optional<String> reasonCode,
      Optional<String> sourceReference,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(kind, "progress fact kind");
    Objects.requireNonNull(occurredAt, "occurrence time");
    reasonCode = Objects.requireNonNull(reasonCode, "reason code");
    sourceReference = Objects.requireNonNull(sourceReference, "source reference");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireFacilityScope(context);
    requireProgressReason(kind, reasonCode);
    validateSourceReference(sourceReference);
    String authority = authorityFor(kind);
    requireAuthority(context, transferId, authority, actionCode(kind));
    CommandReceiptKey receiptKey = commandKey(context, kind, transferId, idempotencyKey);
    Optional<TransferStore.ReceiptClaim> previous =
        store.findProgressReceipt(receiptKey, fingerprint);
    if (previous.isPresent()) {
      TransferProgressCommandResult replay = replay(transferId, previous.orElseThrow());
      if (replay != null) {
        return replay;
      }
    }
    if (kind == TransferProgressFact.Kind.DEPARTURE) {
      TransferProgressTarget observedTarget =
          store.findProgressTarget(transferId).orElseThrow(TransferProgressCommands::notFound);
      requireParticipant(context, observedTarget, transferId, kind);
      if (expectedVersion < 1 || observedTarget.transfer().version() != expectedVersion) {
        throw stateConflict();
      }
      if (!reservationRecovery
          .getObject()
          .hasCurrentActiveReservation(
              transferId, context.correlationId(), context.causationId())) {
        throw stateConflict();
      }
    }
    if (kind == TransferProgressFact.Kind.CAPACITY_RECONFIRMATION) {
      TransferProgressTarget observedTarget =
          store.findProgressTarget(transferId).orElseThrow(TransferProgressCommands::notFound);
      requireParticipant(context, observedTarget, transferId, kind);
      if (expectedVersion < 1
          || observedTarget.transfer().version() != expectedVersion
          || observedTarget.transfer().status() != CoordinatedTransfer.Status.COORDINATION_EXCEPTION
          || !hasReservedIntent(transferId)) {
        throw stateConflict();
      }
      if (!reservationRecovery
          .getObject()
          .hasCurrentActiveReservation(
              transferId, context.correlationId(), context.causationId())) {
        throw stateConflict();
      }
    }

    Optional<String> normalizedReason = reasonCode;
    Optional<String> normalizedSourceReference = sourceReference;
    return execute(
        () -> {
          Instant recordedAt = clock.instant();
          store.configureBoundedReceiptWait();
          TransferStore.ReceiptClaim claim =
              store.claimProgressReceipt(receiptKey, fingerprint, recordedAt);
          TransferProgressCommandResult replay = replay(transferId, claim);
          if (replay != null) {
            return replay;
          }
          TransferProgressTarget target =
              store.lockProgressTarget(transferId).orElseThrow(TransferProgressCommands::notFound);
          requireParticipant(context, target, transferId, kind);
          if (expectedVersion < 1 || target.transfer().version() != expectedVersion) {
            throw stateConflict();
          }
          CoordinatedTransfer.Status current = target.transfer().status();
          if (kind == TransferProgressFact.Kind.CAPACITY_RECONFIRMATION
              && !hasReservedIntent(transferId)) {
            throw stateConflict();
          }
          Transition transition = transition(kind, current);
          TransferProgressFact fact =
              new TransferProgressFact(
                  InternalId.generate(),
                  transferId,
                  target.transfer().journeyId(),
                  kind,
                  Optional.of(current),
                  transition.status(),
                  Optional.ofNullable(transition.exceptionCode()),
                  normalizedReason,
                  normalizedSourceReference,
                  context.actor(),
                  authority,
                  context.scope().organization().orElseThrow(),
                  context.scope().facility().orElseThrow(),
                  occurredAt,
                  recordedAt,
                  Math.addExact(target.transfer().version(), 1),
                  Math.addExact(target.journeyVersion(), 1));
          if (!store.recordProgress(fact)) {
            throw stateConflict();
          }
          auditMutation(context, fact);
          if (kind == TransferProgressFact.Kind.COMPLETION) {
            recordCompletionEvent(context, fact);
          }
          store.completeProgressReceipt(
              receiptKey,
              HttpStatus.CREATED.value(),
              transferId,
              fact.transferVersion(),
              recordedAt);
          return new TransferProgressCommandResult(fact, false);
        });
  }

  public TransferProgressCorrectionResult correct(
      ExecutionContext context,
      InternalId transferId,
      InternalId progressFactId,
      TransferProgressCorrection.Kind correctionKind,
      Optional<Instant> correctedOccurredAt,
      Optional<String> correctedResultCode,
      String reasonCode,
      long expectedTransferVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(transferId, "Transfer id");
    Objects.requireNonNull(progressFactId, "corrected progress fact id");
    Objects.requireNonNull(correctionKind, "correction kind");
    correctedOccurredAt = Objects.requireNonNull(correctedOccurredAt, "corrected occurrence time");
    correctedResultCode = Objects.requireNonNull(correctedResultCode, "corrected result code");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireFacilityScope(context);
    validateCorrection(correctionKind, correctedOccurredAt, correctedResultCode, reasonCode);

    TransferProgressTarget initialTarget =
        store.findProgressTarget(transferId).orElseThrow(TransferProgressCommands::notFound);
    TransferProgressFact original =
        store
            .findProgressFact(transferId, progressFactId)
            .orElseThrow(TransferProgressCommands::notFound);
    String authority = authorityFor(original.kind());
    requireAuthority(context, transferId, authority, "TRANSFER_PROGRESS_CORRECT");
    requireParticipant(context, initialTarget, transferId, original.kind());

    CommandReceiptKey receiptKey =
        correctionKey(context, transferId, progressFactId, idempotencyKey);
    Optional<TransferStore.ReceiptClaim> previous =
        store.findProgressReceipt(receiptKey, fingerprint);
    if (previous.isPresent()) {
      TransferProgressCorrectionResult replay =
          replayCorrection(transferId, previous.orElseThrow());
      if (replay != null) {
        return replay;
      }
    }

    Optional<Instant> normalizedCorrectedAt = correctedOccurredAt;
    Optional<String> normalizedCorrectedResult = correctedResultCode;
    return execute(
        () -> {
          Instant recordedAt = clock.instant();
          store.configureBoundedReceiptWait();
          TransferStore.ReceiptClaim claim =
              store.claimProgressReceipt(receiptKey, fingerprint, recordedAt);
          TransferProgressCorrectionResult replay = replayCorrection(transferId, claim);
          if (replay != null) {
            return replay;
          }
          TransferProgressTarget target =
              store.lockProgressTarget(transferId).orElseThrow(TransferProgressCommands::notFound);
          TransferProgressFact lockedOriginal =
              store
                  .findProgressFact(transferId, progressFactId)
                  .orElseThrow(TransferProgressCommands::notFound);
          requireParticipant(context, target, transferId, lockedOriginal.kind());
          if (expectedTransferVersion < 2
              || target.transfer().version() != expectedTransferVersion) {
            throw stateConflict();
          }
          long aggregateSequence = Math.addExact(target.journeyVersion(), 1);
          TransferProgressCorrection correction =
              new TransferProgressCorrection(
                  InternalId.generate(),
                  transferId,
                  target.transfer().journeyId(),
                  progressFactId,
                  correctionKind,
                  normalizedCorrectedAt,
                  normalizedCorrectedResult,
                  reasonCode,
                  context.actor(),
                  authorityFor(lockedOriginal.kind()),
                  context.scope().organization().orElseThrow(),
                  context.scope().facility().orElseThrow(),
                  recordedAt,
                  target.transfer().version(),
                  aggregateSequence);
          if (!store.recordProgressCorrection(correction)) {
            throw stateConflict();
          }
          auditCorrection(context, correction);
          store.completeProgressReceipt(
              receiptKey,
              HttpStatus.CREATED.value(),
              transferId,
              correction.aggregateSequence(),
              recordedAt);
          return new TransferProgressCorrectionResult(correction, false);
        });
  }

  public TransferProgressHistory history(ExecutionContext context, InternalId transferId) {
    Objects.requireNonNull(context, "execution context");
    requireFacilityScope(context);
    TransferProgressTarget target =
        store.findProgressTarget(transferId).orElseThrow(TransferProgressCommands::notFound);
    requireAuthority(
        context, transferId, TransferAuthorities.progress().code(), "TRANSFER_PROGRESS_READ");
    requireParticipant(context, target, transferId, TransferProgressFact.Kind.ARRIVAL);
    List<TransferProgressFact> facts = store.findProgressFacts(transferId);
    List<TransferProgressCorrection> corrections = store.findProgressCorrections(transferId);
    recordRead(context, transferId);
    return new TransferProgressHistory(
        target.transfer().journeyId(), target.transfer(), facts, corrections);
  }

  private static void validateCorrection(
      TransferProgressCorrection.Kind kind,
      Optional<Instant> correctedOccurredAt,
      Optional<String> correctedResultCode,
      String reasonCode) {
    Objects.requireNonNull(kind, "correction kind");
    Objects.requireNonNull(correctedOccurredAt, "corrected occurrence time");
    Objects.requireNonNull(correctedResultCode, "corrected result code");
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "A structured correction reason code is required.");
    }
    boolean validValues =
        kind == TransferProgressCorrection.Kind.OCCURRENCE_TIME
            ? correctedOccurredAt.isPresent() && correctedResultCode.isEmpty()
            : correctedOccurredAt.isEmpty() && correctedResultCode.isPresent();
    if (!validValues
        || correctedResultCode.stream().anyMatch(value -> !value.matches("[A-Z][A-Z0-9_]{1,79}"))) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "Correction values must match the selected correction kind and use a structured code.");
    }
  }

  private Transition transition(
      TransferProgressFact.Kind kind, CoordinatedTransfer.Status current) {
    return switch (kind) {
      case DEPARTURE -> {
        if (current != CoordinatedTransfer.Status.READY) {
          throw stateConflict();
        }
        yield new Transition(CoordinatedTransfer.Status.IN_TRANSIT, null);
      }
      case INTERRUPTION -> {
        if (current != CoordinatedTransfer.Status.IN_TRANSIT) {
          throw stateConflict();
        }
        yield new Transition(CoordinatedTransfer.Status.INTERRUPTED, null);
      }
      case RESUMPTION -> {
        if (current != CoordinatedTransfer.Status.INTERRUPTED) {
          throw stateConflict();
        }
        yield new Transition(CoordinatedTransfer.Status.IN_TRANSIT, null);
      }
      case ARRIVAL -> {
        String exceptionCode =
            switch (current) {
              case AWAITING_RESERVATION -> "ARRIVAL_BEFORE_RESERVATION_CONFIRMATION";
              case COORDINATION_EXCEPTION -> "ARRIVAL_DURING_COORDINATION_EXCEPTION";
              case READY -> "ARRIVAL_BEFORE_DEPARTURE_RECORDED";
              case INTERRUPTED -> "ARRIVAL_REPORTED_AFTER_INTERRUPTION";
              case IN_TRANSIT -> null;
              default -> throw arrivalInvalid();
            };
        yield new Transition(CoordinatedTransfer.Status.ARRIVED, exceptionCode);
      }
      case HANDOVER -> {
        if (current != CoordinatedTransfer.Status.ARRIVED) {
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.TRANSFER_HANDOVER_REQUIRED,
              "Arrival must be recorded before receiving Handover acknowledgement.");
        }
        yield new Transition(CoordinatedTransfer.Status.HANDED_OVER, null);
      }
      case COMPLETION -> {
        if (current != CoordinatedTransfer.Status.HANDED_OVER) {
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.TRANSFER_HANDOVER_REQUIRED,
              "Receiving Handover acknowledgement is required before coordination completion.");
        }
        yield new Transition(CoordinatedTransfer.Status.COMPLETED, null);
      }
      case CANCELLATION -> {
        if (current != CoordinatedTransfer.Status.AWAITING_RESERVATION
            && current != CoordinatedTransfer.Status.READY
            && current != CoordinatedTransfer.Status.COORDINATION_EXCEPTION) {
          throw stateConflict();
        }
        yield new Transition(CoordinatedTransfer.Status.CANCELLATION_PENDING_RELEASE, null);
      }
      case FINAL_ABANDONMENT -> {
        if (current != CoordinatedTransfer.Status.INTERRUPTED) {
          throw stateConflict();
        }
        yield new Transition(CoordinatedTransfer.Status.CANCELLATION_PENDING_RELEASE, null);
      }
      case CANCELLATION_RELEASE -> {
        if (current != CoordinatedTransfer.Status.CANCELLATION_PENDING_RELEASE) {
          throw stateConflict();
        }
        yield new Transition(CoordinatedTransfer.Status.CANCELLED, null);
      }
      case REFERRAL_CANCELLATION -> {
        if (current != CoordinatedTransfer.Status.AWAITING_RESERVATION
            && current != CoordinatedTransfer.Status.READY
            && current != CoordinatedTransfer.Status.COORDINATION_EXCEPTION) {
          throw stateConflict();
        }
        yield new Transition(CoordinatedTransfer.Status.CANCELLATION_PENDING_RELEASE, null);
      }
      case CAPACITY_EXCEPTION -> {
        if (current != CoordinatedTransfer.Status.AWAITING_RESERVATION
            && current != CoordinatedTransfer.Status.READY
            && current != CoordinatedTransfer.Status.COORDINATION_EXCEPTION) {
          throw stateConflict();
        }
        yield new Transition(CoordinatedTransfer.Status.COORDINATION_EXCEPTION, null);
      }
      case CAPACITY_RECONFIRMATION -> {
        if (current != CoordinatedTransfer.Status.COORDINATION_EXCEPTION) {
          throw stateConflict();
        }
        yield new Transition(CoordinatedTransfer.Status.READY, null);
      }
    };
  }

  private void recordCompletionEvent(ExecutionContext context, TransferProgressFact fact) {
    TransferJourney journey =
        store.findJourney(fact.journeyId()).orElseThrow(TransferProgressCommands::unavailable);
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", journey.referralId().toString());
    payload.put("transferReference", fact.transferId().toString());
    payload.put("outcome", "COMPLETED");
    fact.reasonCode().ifPresent(value -> payload.put("reasonCode", value));
    EventEnvelope event =
        new EventEnvelope(
            InternalId.generate(),
            EventType.parse("transfer.completed.v1"),
            "transfer",
            new OccurredAt(fact.occurredAt()),
            new RecordedAt(fact.recordedAt()),
            context,
            Optional.of(
                new AggregatePosition(
                    "transfer-journey/" + fact.journeyId(), fact.aggregateSequence())),
            payload);
    outboxWriter.record(event);
  }

  private void requireFacilityScope(ExecutionContext context) {
    if (context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()
        || !DIRECTORY_ISSUER.equals(context.scope().organization().orElseThrow().issuer().value())
        || !DIRECTORY_ISSUER.equals(context.scope().facility().orElseThrow().issuer().value())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transfer progress requires a validated Directory Facility scope.");
    }
  }

  private void requireParticipant(
      ExecutionContext context,
      TransferProgressTarget target,
      InternalId transferId,
      TransferProgressFact.Kind kind) {
    ExternalReference organization = context.scope().organization().orElseThrow();
    ExternalReference facility = context.scope().facility().orElseThrow();
    boolean receiving =
        target.receivingOrganization().equals(organization)
            && target.receivingFacility().equals(facility);
    boolean origin =
        target.originOrganization().equals(organization)
            && target.originFacility().filter(facility::equals).isPresent();
    boolean permitted =
        switch (kind) {
          case HANDOVER, COMPLETION, CAPACITY_RECONFIRMATION -> receiving;
          default -> receiving || origin;
        };
    if (!permitted) {
      recordDenial(context, transferId, actionCode(kind), "TRANSFER_SCOPE_MISMATCH");
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.TRANSFER_SCOPE_MISMATCH,
          "The Transfer is outside the authorized participant Facility scope.");
    }
  }

  private void requireAuthority(
      ExecutionContext context, InternalId transferId, String authorityCode, String action) {
    AuthorizationDecision decision = authorization.getObject().evaluate(context, authorityCode);
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    recordDenial(
        context,
        transferId,
        action,
        unavailable ? "AUTHORIZATION_UNAVAILABLE" : "AUTHORIZATION_DENIED");
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer authorization is temporarily unavailable."
            : "The actor is not authorized for this Transfer function.");
  }

  private String authorityFor(TransferProgressFact.Kind kind) {
    return switch (kind) {
      case DEPARTURE, INTERRUPTION, RESUMPTION, ARRIVAL -> TransferAuthorities.progress().code();
      case HANDOVER -> TransferAuthorities.handover().code();
      case COMPLETION -> TransferAuthorities.completion().code();
      case CANCELLATION -> TransferAuthorities.progress().code();
      case FINAL_ABANDONMENT -> TransferAuthorities.progress().code();
      case CANCELLATION_RELEASE -> TransferAuthorities.reservationReconciliation().code();
      case REFERRAL_CANCELLATION, CAPACITY_EXCEPTION ->
          TransferAuthorities.reservationReconciliation().code();
      case CAPACITY_RECONFIRMATION -> TransferAuthorities.acceptance().code();
    };
  }

  private static void requireProgressReason(
      TransferProgressFact.Kind kind, Optional<String> reasonCode) {
    if ((kind == TransferProgressFact.Kind.INTERRUPTION
            || kind == TransferProgressFact.Kind.COMPLETION
            || kind == TransferProgressFact.Kind.CANCELLATION
            || kind == TransferProgressFact.Kind.FINAL_ABANDONMENT
            || kind == TransferProgressFact.Kind.REFERRAL_CANCELLATION
            || kind == TransferProgressFact.Kind.CAPACITY_EXCEPTION
            || kind == TransferProgressFact.Kind.CAPACITY_RECONFIRMATION)
        && reasonCode.isEmpty()) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "This Transfer progress fact requires a structured reason code.");
    }
    reasonCode.ifPresent(
        value -> {
          if (!value.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                PlatformErrorCode.TRANSFER_REQUEST_INVALID,
                "A valid structured reason code is required.");
          }
        });
  }

  private static void validateSourceReference(Optional<String> sourceReference) {
    sourceReference.ifPresent(
        value -> {
          if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
            throw problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                PlatformErrorCode.TRANSFER_REQUEST_INVALID,
                "A safe source reference is required.");
          }
        });
  }

  private String actionCode(TransferProgressFact.Kind kind) {
    return "TRANSFER_" + kind.name();
  }

  private boolean hasReservedIntent(InternalId transferId) {
    return store
        .findReservationIntent(transferId)
        .filter(intent -> intent.outcome() == CapacityReservationIntent.Outcome.RESERVED)
        .filter(intent -> intent.reservationId().isPresent())
        .isPresent();
  }

  private CommandReceiptKey commandKey(
      ExecutionContext context,
      TransferProgressFact.Kind kind,
      InternalId transferId,
      String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context,
          "transfer.progress." + kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
          "transfers/" + transferId,
          idempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "A valid Idempotency-Key is required for Transfer progress.");
    }
  }

  private CommandReceiptKey correctionKey(
      ExecutionContext context,
      InternalId transferId,
      InternalId progressFactId,
      String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context,
          "transfer.progress.correction",
          "transfers/" + transferId + "/progress-facts/" + progressFactId,
          idempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "A valid Idempotency-Key is required for a Transfer progress correction.");
    }
  }

  private TransferProgressCommandResult replay(
      InternalId transferId, TransferStore.ReceiptClaim claim) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The Idempotency-Key was already used for a different Transfer command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
      case REPLAY -> {
        if (!transferId.equals(claim.resultReference().orElseThrow())) {
          throw stateConflict();
        }
        TransferProgressFact fact =
            store
                .findProgressFactByVersion(transferId, claim.resultVersion().orElseThrow())
                .orElseThrow(TransferProgressCommands::unavailable);
        yield new TransferProgressCommandResult(fact, true);
      }
    };
  }

  private TransferProgressCorrectionResult replayCorrection(
      InternalId transferId, TransferStore.ReceiptClaim claim) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The Idempotency-Key was already used for a different Transfer command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
      case REPLAY -> {
        if (!transferId.equals(claim.resultReference().orElseThrow())) {
          throw stateConflict();
        }
        TransferProgressCorrection correction =
            store
                .findProgressCorrectionBySequence(transferId, claim.resultVersion().orElseThrow())
                .orElseThrow(TransferProgressCommands::unavailable);
        yield new TransferProgressCorrectionResult(correction, true);
      }
    };
  }

  private void auditMutation(ExecutionContext context, TransferProgressFact fact) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              new AuditEvidence(
                  AuditActionKind.PROTECTED_MUTATION,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  actionCode(fact.kind()),
                  "CoordinatedTransfer",
                  Optional.of(fact.transferId()),
                  fact.recordedAt(),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Transfer progress fact and its required audit evidence could not be committed.");
    }
  }

  private void auditCorrection(ExecutionContext context, TransferProgressCorrection correction) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              new AuditEvidence(
                  AuditActionKind.PROTECTED_MUTATION,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  "TRANSFER_PROGRESS_CORRECT",
                  "TransferProgressCorrection",
                  Optional.of(correction.id()),
                  correction.recordedAt(),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Transfer progress correction and its required audit evidence could not be committed.");
    }
  }

  private void recordRead(ExecutionContext context, InternalId transferId) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SENSITIVE_READ,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  "TRANSFER_PROGRESS_READ",
                  "CoordinatedTransfer",
                  Optional.of(transferId),
                  clock.instant(),
                  AuditOutcome.SUCCEEDED,
                  Optional.empty(),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transfer progress history is unavailable because its required audit record could not be stored.");
    }
  }

  private void recordDenial(
      ExecutionContext context, InternalId transferId, String action, String reasonCode) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  action,
                  "CoordinatedTransfer",
                  Optional.of(transferId),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of(reasonCode),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId(),
                  Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private static AuditTargetContext targetContext(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
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

  private <T> T execute(Supplier<T> operation) {
    try {
      return transaction.execute(status -> operation.get());
    } catch (CannotAcquireLockException busy) {
      throw problem(
          HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
    } catch (DuplicateKeyException conflict) {
      throw stateConflict();
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND, PlatformErrorCode.TRANSFER_NOT_FOUND, "The Transfer was not found.");
  }

  private static PlatformProblemException arrivalInvalid() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_ARRIVAL_INVALID,
        "Arrival cannot be recorded from the current Transfer state.");
  }

  private static PlatformProblemException stateConflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_STATE_CONFLICT,
        "The Transfer changed or does not allow this progress fact; reload it before retrying.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "The Transfer progress command could not be committed safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private record Transition(CoordinatedTransfer.Status status, String exceptionCode) {}
}
