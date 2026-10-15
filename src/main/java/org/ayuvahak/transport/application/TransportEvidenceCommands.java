package org.ayuvahak.transport.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
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
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transport.domain.TransportAssignmentObservation;
import org.ayuvahak.transport.domain.TransportAuthorities;
import org.ayuvahak.transport.domain.TransportEtaObservation;
import org.ayuvahak.transport.domain.TransportRequest;
import org.ayuvahak.transport.domain.TransportStatusObservation;
import org.ayuvahak.transport.domain.TransportStatusPolicy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Audited owner commands for manual assignment, status, and ETA evidence. */
@Service
public final class TransportEvidenceCommands {

  private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);
  private static final Duration ETA_MAX_AGE = Duration.ofHours(1);
  private static final Duration ETA_VALIDITY = Duration.ofMinutes(15);
  private static final String BUSY_DETAIL =
      "A matching Transport evidence command is still being processed; retry with the same idempotency key.";

  private final TransportRequestStore requests;
  private final TransportEvidenceStore evidence;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;

  public TransportEvidenceCommands(
      TransportRequestStore requests,
      TransportEvidenceStore evidence,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      OutboxWriter outbox,
      ObjectMapper mapper,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.requests = Objects.requireNonNull(requests, "Transport request store");
    this.evidence = Objects.requireNonNull(evidence, "Transport evidence store");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.outbox = Objects.requireNonNull(outbox, "outbox writer");
    this.mapper = Objects.requireNonNull(mapper, "object mapper");
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public TransportEvidenceCommandResult recordStatus(
      ExecutionContext context,
      InternalId requestId,
      StatusInput input,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireContext(context, requestId);
    requireAuthority(context, requestId, TransportAuthorities.status().code());
    TransportEvidenceCommandResult result =
        execute(
            context,
            requestId,
            input.sourceNamespace(),
            input.sourceObservationId(),
            "transport.status.observe",
            "status-observations",
            idempotencyKey,
            fingerprint,
            (key, at) -> persistStatus(context, requestId, input, fingerprint, key, at));
    if (!"APPLIED".equals(result.evidenceOutcome())) {
      throw problem(
          HttpStatus.CONFLICT,
          result.evidenceOutcome().equals("FUTURE_SKEWED")
              ? PlatformErrorCode.TRANSPORT_EVIDENCE_STALE
              : PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
          "Transport retained the status evidence for reconciliation without changing request state.");
    }
    return result;
  }

  public TransportEvidenceCommandResult recordUnfulfilled(
      ExecutionContext context,
      InternalId requestId,
      StatusInput input,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireContext(context, requestId);
    if (input.status() != TransportRequest.Status.UNFULFILLED) {
      throw invalid("The unfulfilled operation must record an UNFULFILLED outcome.");
    }
    requireAuthority(context, requestId, TransportAuthorities.dispatchCoordination().code());
    return execute(
        context,
        requestId,
        input.sourceNamespace(),
        input.sourceObservationId(),
        "transport.unfulfilled.record",
        "unfulfilled-outcomes",
        idempotencyKey,
        fingerprint,
        (key, at) -> persistStatus(context, requestId, input, fingerprint, key, at));
  }

  public TransportEvidenceCommandResult recordAssignment(
      ExecutionContext context,
      InternalId requestId,
      AssignmentInput input,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireContext(context, requestId);
    requireAuthority(context, requestId, TransportAuthorities.dispatchCoordination().code());
    return execute(
        context,
        requestId,
        input.sourceNamespace(),
        input.sourceObservationId(),
        "transport.assignment.record",
        "assignments",
        idempotencyKey,
        fingerprint,
        (key, at) -> persistAssignment(context, requestId, input, fingerprint, key, at));
  }

  public TransportEvidenceCommandResult recordEta(
      ExecutionContext context,
      InternalId requestId,
      EtaInput input,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    requireContext(context, requestId);
    requireAuthority(context, requestId, TransportAuthorities.status().code());
    return execute(
        context,
        requestId,
        input.sourceNamespace(),
        input.sourceObservationId(),
        "transport.eta.record",
        "eta-observations",
        idempotencyKey,
        fingerprint,
        (key, at) -> persistEta(context, requestId, input, fingerprint, key, at));
  }

  private TransportEvidenceCommandResult execute(
      ExecutionContext context,
      InternalId requestId,
      String sourceNamespace,
      String sourceObservationId,
      String operation,
      String target,
      String idempotencyKey,
      RequestFingerprint fingerprint,
      EvidenceWork persist) {
    Objects.requireNonNull(fingerprint, "request fingerprint");
    try {
      return transaction.execute(
          ignored -> {
            Instant now = clock.instant();
            requests.configureBoundedReceiptWait();
            CommandReceiptKey receiptKey =
                commandKey(context, operation, target, requestId, idempotencyKey);
            TransportRequestStore.Claim claim = requests.claimReceipt(receiptKey, fingerprint, now);
            if (claim.state() == TransportRequestStore.ClaimState.KEY_REUSED) {
              throw problem(
                  HttpStatus.CONFLICT,
                  PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                  "The Idempotency-Key was already used for different Transport evidence.");
            }
            if (claim.state() == TransportRequestStore.ClaimState.IN_PROGRESS) {
              throw problem(
                  HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
            }
            if (claim.state() == TransportRequestStore.ClaimState.REPLAY) {
              TransportRequest request =
                  requests
                      .find(claim.resultReference().orElseThrow())
                      .orElseThrow(TransportEvidenceCommands::unavailable);
              Optional<TransportEvidenceStore.EvidenceReplay> replay =
                  findSource(requestId, sourceNamespace, sourceObservationId, target);
              TransportEvidenceStore.EvidenceReplay source =
                  replay.orElseThrow(TransportEvidenceCommands::unavailable);
              if (!source.fingerprint().equals(fingerprint.value())) {
                throw problem(
                    HttpStatus.CONFLICT,
                    PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "The Transport evidence receipt does not match the original evidence.");
              }
              return result(source, requestId, request.status().name(), true, true);
            }
            return persist.run(receiptKey, now);
          });
    } catch (CannotAcquireLockException busy) {
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DuplicateKeyException conflict) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSPORT_EVIDENCE_CONFLICT,
          "The source evidence identity conflicts with an existing Transport observation.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSPORT_UNAVAILABLE,
          "Transport could not persist evidence safely.");
    }
  }

  private TransportEvidenceCommandResult persistStatus(
      ExecutionContext context,
      InternalId requestId,
      StatusInput input,
      RequestFingerprint fingerprint,
      CommandReceiptKey receiptKey,
      Instant now) {
    TransportRequest request = lockRequest(context, requestId);
    Optional<TransportEvidenceStore.EvidenceReplay> previous =
        evidence.findStatus(requestId, input.sourceNamespace(), input.sourceObservationId());
    if (previous.isPresent()) {
      return recoverDuplicate(request, previous.orElseThrow(), fingerprint, receiptKey, now);
    }

    Optional<Long> latestSequence =
        evidence.latestAppliedStatusSequence(requestId, input.sourceNamespace());
    Optional<Instant> latestTime =
        evidence.latestAppliedStatusTime(requestId, input.sourceNamespace());
    boolean timeSkewed = input.occurredAt().isAfter(now.plus(FUTURE_SKEW));
    boolean outOfOrder =
        (input.sourceSequence().isPresent()
                && latestSequence
                    .filter(value -> input.sourceSequence().getAsLong() <= value)
                    .isPresent())
            || latestTime.filter(value -> !input.occurredAt().isAfter(value)).isPresent();
    boolean transitionAllowed = TransportStatusPolicy.permits(request.status(), input.status());
    TransportStatusObservation.Outcome outcome =
        timeSkewed
            ? TransportStatusObservation.Outcome.FUTURE_SKEWED
            : (outOfOrder || !transitionAllowed)
                ? TransportStatusObservation.Outcome.RECONCILIATION_REQUIRED
                : TransportStatusObservation.Outcome.APPLIED;
    TransportRequest.Status resultingStatus =
        outcome == TransportStatusObservation.Outcome.APPLIED ? input.status() : request.status();
    long resultingVersion =
        outcome == TransportStatusObservation.Outcome.APPLIED
            ? request.version() + 1
            : request.version();
    if (outcome == TransportStatusObservation.Outcome.APPLIED
        && !evidence.updateStatus(
            requestId, request.version(), resultingStatus, resultingVersion, now)) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSPORT_STATE_CONFLICT,
          "Transport request state changed during status reconciliation.");
    }

    String reason =
        input
            .reasonCode()
            .orElseGet(
                () ->
                    switch (input.status()) {
                      case INTERRUPTED -> "TRANSPORT_INTERRUPTED";
                      case UNFULFILLED -> "TRANSPORT_UNFULFILLED";
                      default ->
                          outcome == TransportStatusObservation.Outcome.APPLIED
                              ? "STATUS_ACCEPTED"
                              : outcome.name();
                    });
    String authorityCode =
        input.status() == TransportRequest.Status.UNFULFILLED
            ? TransportAuthorities.dispatchCoordination().code()
            : TransportAuthorities.status().code();
    TransportStatusObservation observation =
        new TransportStatusObservation(
            InternalId.generate(),
            requestId,
            input.sourceNamespace(),
            input.sourceObservationId(),
            input.sourceSequence(),
            input.status(),
            input.occurredAt(),
            now,
            context.actor(),
            authorityCode,
            context.scope().organization().orElseThrow(),
            context.scope().facility().orElseThrow(),
            context.correlationId(),
            outcome,
            resultingStatus,
            Optional.of(reason),
            resultingVersion);
    if (!evidence.insertStatus(observation, fingerprint.value())) {
      throw unavailable();
    }
    auditMutation(
        context, "TRANSPORT_STATUS_OBSERVATION", requestId, outcome.name(), authorityCode, now);
    movementEventType(input.status())
        .ifPresent(type -> recordMovementEvent(context, request, observation, type));
    requests.completeReceipt(receiptKey, requestId, now);
    return new TransportEvidenceCommandResult(
        observation.id(),
        requestId,
        resultingStatus.name(),
        outcome.name(),
        Optional.empty(),
        false,
        false);
  }

  private TransportEvidenceCommandResult persistAssignment(
      ExecutionContext context,
      InternalId requestId,
      AssignmentInput input,
      RequestFingerprint fingerprint,
      CommandReceiptKey receiptKey,
      Instant now) {
    TransportRequest request = lockRequest(context, requestId);
    Optional<TransportEvidenceStore.EvidenceReplay> previous =
        evidence.findAssignment(requestId, input.sourceNamespace(), input.sourceObservationId());
    if (previous.isPresent()) {
      return recoverDuplicate(request, previous.orElseThrow(), fingerprint, receiptKey, now);
    }
    if (input.occurredAt().isAfter(now.plus(FUTURE_SKEW))) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSPORT_EVIDENCE_STALE,
          "Transport rejected assignment evidence with a future source time.");
    }
    if (request.status() != TransportRequest.Status.AWAITING_ASSIGNMENT) {
      throw stateConflict("An assignment is accepted only while the request awaits assignment.");
    }
    if (evidence
        .latestAppliedStatusTime(requestId, input.sourceNamespace())
        .filter(value -> input.occurredAt().isBefore(value))
        .isPresent()) {
      throw stateConflict("The assignment evidence predates the accepted status for this source.");
    }

    long nextVersion = request.version() + 1;
    TransportAssignmentObservation observation =
        new TransportAssignmentObservation(
            InternalId.generate(),
            requestId,
            input.sourceNamespace(),
            input.sourceObservationId(),
            input.assignmentReference(),
            input.occurredAt(),
            now,
            context.actor(),
            TransportAuthorities.dispatchCoordination().code(),
            context.scope().organization().orElseThrow(),
            context.scope().facility().orElseThrow(),
            context.correlationId(),
            nextVersion);
    if (!evidence.insertAssignment(observation, fingerprint.value())) {
      throw unavailable();
    }
    if (!evidence.updateStatus(
        requestId, request.version(), TransportRequest.Status.ASSIGNED, nextVersion, now)) {
      throw stateConflict("Transport request state changed while recording assignment.");
    }
    auditMutation(
        context,
        "TRANSPORT_ASSIGNMENT_RECORDED",
        requestId,
        "ASSIGNMENT_ACCEPTED",
        TransportAuthorities.dispatchCoordination().code(),
        now);
    requests.completeReceipt(receiptKey, requestId, now);
    return new TransportEvidenceCommandResult(
        observation.id(),
        requestId,
        TransportRequest.Status.ASSIGNED.name(),
        "APPLIED",
        Optional.empty(),
        false,
        false);
  }

  private TransportEvidenceCommandResult persistEta(
      ExecutionContext context,
      InternalId requestId,
      EtaInput input,
      RequestFingerprint fingerprint,
      CommandReceiptKey receiptKey,
      Instant now) {
    TransportRequest request = lockRequest(context, requestId);
    Optional<TransportEvidenceStore.EvidenceReplay> previous =
        evidence.findEta(requestId, input.sourceNamespace(), input.sourceObservationId());
    if (previous.isPresent()) {
      return recoverDuplicate(request, previous.orElseThrow(), fingerprint, receiptKey, now);
    }
    if (input.observedAt().isAfter(now.plus(FUTURE_SKEW))
        || input.observedAt().isBefore(now.minus(ETA_MAX_AGE))) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSPORT_EVIDENCE_STALE,
          "Transport rejected an ETA outside the supported freshness window.");
    }
    if (!etaAllowed(request.status())) {
      throw stateConflict(
          "ETA evidence is accepted only for an active assigned Transport request.");
    }
    if (evidence
        .latestEtaTime(requestId, input.sourceNamespace())
        .filter(value -> !input.observedAt().isAfter(value))
        .isPresent()) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSPORT_EVIDENCE_STALE,
          "The ETA observation is not newer than the last accepted source observation.");
    }
    if (!input.estimatedArrivalAt().isAfter(input.observedAt())) {
      throw invalid("An ETA must be later than the time at which it was observed.");
    }
    Instant validUntil = now.plus(ETA_VALIDITY);
    TransportEtaObservation observation =
        new TransportEtaObservation(
            InternalId.generate(),
            requestId,
            input.sourceNamespace(),
            input.sourceObservationId(),
            input.estimatedArrivalAt(),
            input.observedAt(),
            now,
            validUntil,
            context.actor(),
            TransportAuthorities.status().code(),
            context.scope().organization().orElseThrow(),
            context.scope().facility().orElseThrow(),
            context.correlationId());
    if (!evidence.insertEta(observation, fingerprint.value())) {
      throw unavailable();
    }
    auditMutation(
        context,
        "TRANSPORT_ETA_OBSERVATION",
        requestId,
        "ETA_RECORDED_AS_NON_GUARANTEE",
        TransportAuthorities.status().code(),
        now);
    requests.completeReceipt(receiptKey, requestId, now);
    return new TransportEvidenceCommandResult(
        observation.id(),
        requestId,
        request.status().name(),
        "RECORDED",
        Optional.of(validUntil),
        false,
        false);
  }

  private TransportEvidenceCommandResult recoverDuplicate(
      TransportRequest request,
      TransportEvidenceStore.EvidenceReplay source,
      RequestFingerprint fingerprint,
      CommandReceiptKey receiptKey,
      Instant now) {
    if (!source.fingerprint().equals(fingerprint.value())) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSPORT_EVIDENCE_CONFLICT,
          "The source observation identifier is already attached to different evidence.");
    }
    requests.completeReceipt(receiptKey, request.id(), now);
    return result(source, request.id(), request.status().name(), false, true);
  }

  private Optional<TransportEvidenceStore.EvidenceReplay> findSource(
      InternalId requestId, String sourceNamespace, String sourceObservationId, String target) {
    return switch (target) {
      case "status-observations" ->
          evidence.findStatus(requestId, sourceNamespace, sourceObservationId);
      case "assignments" ->
          evidence.findAssignment(requestId, sourceNamespace, sourceObservationId);
      case "eta-observations" -> evidence.findEta(requestId, sourceNamespace, sourceObservationId);
      default -> Optional.empty();
    };
  }

  private TransportRequest lockRequest(ExecutionContext context, InternalId requestId) {
    TransportRequest request =
        requests.lock(requestId).orElseThrow(TransportEvidenceCommands::notFound);
    requireMatchingFacility(context, request);
    return request;
  }

  private static void requireContext(ExecutionContext context, InternalId requestId) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(requestId, "Transport request id");
    if (context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transport evidence requires explicit Facility execution scope.");
    }
  }

  private void requireAuthority(ExecutionContext context, InternalId requestId, String authority) {
    AuthorizationDecision decision = authorization.evaluate(context, authority);
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
              "TRANSPORT_EVIDENCE_DENIED",
              "TransportRequest",
              Optional.of(requestId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
              authenticationEvidence(context),
              AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transport withheld the evidence because denial audit is unavailable.");
    }
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transport authorization is temporarily unavailable."
            : "The actor is not authorized to record this Transport evidence at this scope.");
  }

  private void requireMatchingFacility(ExecutionContext context, TransportRequest request) {
    if (context.scope().facility().filter(request.originFacility()::equals).isEmpty()) {
      auditDeniedScope(context, request.id());
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Transport evidence must use the request's origin Facility scope.");
    }
  }

  private void auditDeniedScope(ExecutionContext context, InternalId requestId) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSPORT_EVIDENCE_SCOPE_DENIED",
              "TransportRequest",
              Optional.of(requestId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of("ORIGIN_SCOPE_MISMATCH"),
              authenticationEvidence(context),
              AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Transport withheld the evidence because scope-denial audit is unavailable.");
    }
  }

  private void auditMutation(
      ExecutionContext context,
      String action,
      InternalId requestId,
      String reason,
      String authority,
      Instant at) {
    try {
      audit.recordMutation(
          new AuditEvidence(
              AuditActionKind.PROTECTED_MUTATION,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              action,
              "TransportRequest",
              Optional.of(requestId),
              at,
              AuditOutcome.SUCCEEDED,
              Optional.of(reason),
              authenticationEvidence(context),
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
          "Transport evidence and its required audit could not be committed.");
    }
  }

  private void recordMovementEvent(
      ExecutionContext context,
      TransportRequest request,
      TransportStatusObservation observation,
      EventType eventType) {
    var payload = mapper.createObjectNode();
    payload.put("transferReference", request.transferId().toString());
    payload.put("transportRequestReference", request.id().toString());
    payload.put("originFacilityReference", request.originFacility().value());
    payload.put("destinationFacilityReference", request.destinationFacility().value());
    payload.put("evidenceReference", observation.id().toString());
    payload.put("evidenceKind", observation.status().name());
    payload.put("sourceNamespace", observation.sourceNamespace());
    observation.sourceSequence().ifPresent(value -> payload.put("sourceSequence", value));
    observation.reasonCode().ifPresent(value -> payload.put("reasonCode", value));
    outbox.record(
        new EventEnvelope(
            InternalId.generate(),
            eventType,
            "transport",
            new OccurredAt(observation.occurredAt()),
            new RecordedAt(observation.recordedAt()),
            context,
            Optional.of(
                new AggregatePosition(
                    "transport-request/" + request.id(), observation.resultingRequestVersion())),
            payload));
  }

  private static Optional<EventType> movementEventType(TransportRequest.Status status) {
    return switch (status) {
      case EN_ROUTE_TO_DESTINATION ->
          Optional.of(EventType.parse("transport.departure-confirmed.v1"));
      case ARRIVED_DESTINATION -> Optional.of(EventType.parse("transport.arrival-reported.v1"));
      case INTERRUPTED -> Optional.of(EventType.parse("transport.interrupted.v1"));
      case UNFULFILLED -> Optional.of(EventType.parse("transport.unfulfilled.v1"));
      default -> Optional.empty();
    };
  }

  private static AuditAuthenticationEvidence authenticationEvidence(ExecutionContext context) {
    return switch (context.authenticationMechanism()) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static CommandReceiptKey commandKey(
      ExecutionContext context,
      String operation,
      String target,
      InternalId requestId,
      String rawKey) {
    try {
      return CommandReceiptKey.from(context, operation, target + "/" + requestId, rawKey);
    } catch (IllegalArgumentException invalid) {
      throw invalid("A valid Idempotency-Key is required for this Transport evidence.");
    }
  }

  private static TransportEvidenceCommandResult result(
      TransportEvidenceStore.EvidenceReplay source,
      InternalId requestId,
      String currentStatus,
      boolean commandReplayed,
      boolean sourceReplayed) {
    return new TransportEvidenceCommandResult(
        source.observationId(),
        requestId,
        source.requestStatus() == null ? currentStatus : source.requestStatus(),
        source.outcome(),
        source.validUntil(),
        commandReplayed,
        sourceReplayed);
  }

  private static boolean etaAllowed(TransportRequest.Status status) {
    return switch (status) {
      case ASSIGNED, EN_ROUTE_TO_ORIGIN, AT_ORIGIN, PATIENT_ONBOARD, EN_ROUTE_TO_DESTINATION ->
          true;
      default -> false;
    };
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND, PlatformErrorCode.NOT_FOUND, "The Transport request was not found.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSPORT_UNAVAILABLE,
        "Transport evidence could not be recovered safely.");
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID, detail);
  }

  private static PlatformProblemException stateConflict(String detail) {
    return problem(HttpStatus.CONFLICT, PlatformErrorCode.TRANSPORT_STATE_CONFLICT, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  public record StatusInput(
      String sourceNamespace,
      String sourceObservationId,
      OptionalLong sourceSequence,
      TransportRequest.Status status,
      Instant occurredAt,
      Optional<String> reasonCode) {
    public StatusInput {
      sourceSequence = Objects.requireNonNull(sourceSequence, "source sequence");
      reasonCode = Objects.requireNonNull(reasonCode, "reason code");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(occurredAt, "occurred at");
    }
  }

  public record AssignmentInput(
      String sourceNamespace,
      String sourceObservationId,
      String assignmentReference,
      Instant occurredAt) {
    public AssignmentInput {
      Objects.requireNonNull(occurredAt, "occurred at");
    }
  }

  public record EtaInput(
      String sourceNamespace,
      String sourceObservationId,
      Instant estimatedArrivalAt,
      Instant observedAt) {
    public EtaInput {
      Objects.requireNonNull(estimatedArrivalAt, "estimated arrival at");
      Objects.requireNonNull(observedAt, "observed at");
    }
  }

  private interface EvidenceWork {
    TransportEvidenceCommandResult run(CommandReceiptKey receiptKey, Instant recordedAt);
  }
}
