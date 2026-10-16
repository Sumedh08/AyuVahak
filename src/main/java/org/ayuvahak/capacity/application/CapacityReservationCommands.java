package org.ayuvahak.capacity.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityAuthorities;
import org.ayuvahak.capacity.domain.CapacityEvidenceFreshness;
import org.ayuvahak.capacity.domain.CapacityEvidenceProjection;
import org.ayuvahak.capacity.domain.CapacityEvidenceProjector;
import org.ayuvahak.capacity.domain.CapacityObservationFact;
import org.ayuvahak.capacity.domain.CapacityReleaseResult;
import org.ayuvahak.capacity.domain.CapacityReservationOutcome;
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.capacity.domain.CapacityValueKind;
import org.ayuvahak.directory.application.DirectoryCapacityLifecycleOrganizationLookup;
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
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** Capacity owner command for atomic, evidence-backed coordination holds. */
@Service
@Lazy
public final class CapacityReservationCommands {

  private static final Logger logger = LoggerFactory.getLogger(CapacityReservationCommands.class);

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String BUSY_DETAIL =
      "A matching Capacity reservation is still being processed; retry with the same key.";

  private final CapacityReservationStore reservations;
  private final CapacityPolicyStore configuration;
  private final CapacityEvidenceStore evidence;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<OutboxWriter> outboxWriter;
  private final DirectoryCapacityLifecycleOrganizationLookup directory;
  private final ExecutionContextFactory contexts;
  private final CapacityReservationLifecycleProperties lifecycleProperties;
  private final ActorReference lifecycleWorker;
  private final CapacityEvidenceProjector projector = new CapacityEvidenceProjector();

  public CapacityReservationCommands(
      @Lazy CapacityReservationStore reservations,
      @Lazy CapacityPolicyStore configuration,
      @Lazy CapacityEvidenceStore evidence,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock,
      ObjectMapper objectMapper,
      ObjectProvider<OutboxWriter> outboxWriter,
      DirectoryCapacityLifecycleOrganizationLookup directory,
      ExecutionContextFactory contexts,
      CapacityReservationLifecycleProperties lifecycleProperties,
      AuthenticationProperties authentication) {
    this.reservations = Objects.requireNonNull(reservations, "reservation store");
    this.configuration = Objects.requireNonNull(configuration, "Capacity configuration");
    this.evidence = Objects.requireNonNull(evidence, "Capacity evidence");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
    this.outboxWriter = Objects.requireNonNull(outboxWriter, "outbox writer");
    this.directory = Objects.requireNonNull(directory, "Directory scope lookup");
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
    this.lifecycleProperties =
        Objects.requireNonNull(lifecycleProperties, "Capacity lifecycle properties");
    this.lifecycleWorker =
        ActorReference.service(
            new IssuerId(authentication.issuer()), lifecycleProperties.getWorkerClientId());
  }

  /** Expires due holds and emits at-risk events after Capacity evidence reduces available holds. */
  public int reconcileLifecycle() {
    int processed = 0;
    for (CapacityReservationStore.LifecycleCandidate candidate :
        reservations.findLifecycleCandidates(clock.instant(), lifecycleProperties.getBatchSize())) {
      try {
        if (reconcileLifecycleCandidate(candidate)) {
          processed++;
        }
      } catch (RuntimeException failure) {
        logger.warn(
            "Capacity reservation lifecycle reconciliation deferred failureType={}",
            failure.getClass().getSimpleName());
      }
    }
    return processed;
  }

  private boolean reconcileLifecycleCandidate(
      CapacityReservationStore.LifecycleCandidate candidate) {
    CorrelationId correlation = CorrelationId.generate();
    ExecutionContext directoryContext =
        contexts.forDeploymentBoundedBackgroundWork(lifecycleWorker, correlation);
    Optional<ExternalReference> organization =
        directory.getCapacityLifecycleFacilityOrganization(directoryContext, candidate.facility());
    if (organization.isEmpty()) {
      return false;
    }
    ExecutionContext context =
        contexts.forBackgroundWork(
            lifecycleWorker,
            RequestedExecutionScope.facility(organization.orElseThrow(), candidate.facility()),
            correlation,
            Optional.empty());
    requireReservationAuthority(context, candidate.transferId());
    return Boolean.TRUE.equals(
        transaction.execute(
            status -> {
              Instant now = clock.instant();
              Optional<CapacityReservationStore.LifecycleCandidate> locked =
                  reservations.lockLifecycleCandidate(candidate.reservationId());
              if (locked.isEmpty()) {
                return false;
              }
              CapacityReservationStore.LifecycleCandidate current = locked.orElseThrow();
              if (current.status() == CapacityReservationStore.LifecycleStatus.RELEASED
                  || current.status() == CapacityReservationStore.LifecycleStatus.EXPIRED) {
                return false;
              }
              if (!current.expiresAt().isAfter(now)) {
                return reservations
                    .changeLifecycleStatus(
                        current.reservationId(),
                        current.status(),
                        CapacityReservationStore.LifecycleStatus.EXPIRED,
                        now)
                    .map(
                        expired -> {
                          auditRelease(
                              context,
                              expired.transferId(),
                              "CAPACITY_RESERVATION_EXPIRE",
                              "EXPIRED",
                              true);
                          recordLifecycleEvent(
                              context,
                              expired,
                              EventType.parse("capacity.reservation-expired.v1"),
                              expired.expiresAt());
                          return true;
                        })
                    .orElse(false);
              }
              if (current.status() != CapacityReservationStore.LifecycleStatus.ACTIVE) {
                if (current.status() == CapacityReservationStore.LifecycleStatus.AT_RISK) {
                  if (isAtRisk(current, now)) {
                    reservations.recordLifecycleCheck(current.reservationId(), now);
                    return true;
                  }
                  return reservations
                      .changeLifecycleStatus(
                          current.reservationId(),
                          CapacityReservationStore.LifecycleStatus.AT_RISK,
                          CapacityReservationStore.LifecycleStatus.ACTIVE,
                          now)
                      .map(
                          recovered -> {
                            auditRelease(
                                context,
                                recovered.transferId(),
                                "CAPACITY_RESERVATION_RECOVER",
                                "CAPACITY_EVIDENCE_SUPPORTS_HOLD",
                                true);
                            recordLifecycleEvent(
                                context,
                                recovered,
                                EventType.parse("capacity.reservation-recovered.v1"),
                                now);
                            return true;
                          })
                      .orElse(false);
                }
                return false;
              }
              if (isAtRisk(current, now)) {
                return reservations
                    .changeLifecycleStatus(
                        current.reservationId(),
                        CapacityReservationStore.LifecycleStatus.ACTIVE,
                        CapacityReservationStore.LifecycleStatus.AT_RISK,
                        now)
                    .map(
                        atRisk -> {
                          auditRelease(
                              context,
                              atRisk.transferId(),
                              "CAPACITY_RESERVATION_AT_RISK",
                              "AT_RISK",
                              true);
                          recordLifecycleEvent(
                              context,
                              atRisk,
                              EventType.parse("capacity.reservation-at-risk.v1"),
                              now);
                          return true;
                        })
                    .orElse(false);
              }
              reservations.recordLifecycleCheck(current.reservationId(), now);
              return true;
            }));
  }

  private boolean isAtRisk(CapacityReservationStore.LifecycleCandidate reservation, Instant at) {
    Optional<org.ayuvahak.capacity.domain.CapacityDimension> dimension =
        configuration.currentDimension(reservation.dimensionCode());
    Optional<org.ayuvahak.capacity.domain.CapacityPolicy> policy =
        configuration.currentPolicy(reservation.dimensionCode());
    CapacityEvidenceProjection projection =
        projector.project(
            reservation.facility(),
            reservation.dimensionCode(),
            dimension,
            policy,
            evidence.findForProjection(reservation.facility(), reservation.dimensionCode()),
            at);
    if (projection.freshness() != CapacityEvidenceFreshness.CURRENT
        || projection.valueKind().filter(kind -> kind == CapacityValueKind.QUANTITY).isEmpty()
        || projection.unit().filter(reservation.unit()::equals).isEmpty()
        || projection.quantity().isEmpty()) {
      return true;
    }
    BigDecimal held =
        reservations.sumActiveReservations(
            reservation.facility().issuer().value(),
            reservation.facility().value(),
            reservation.dimensionCode(),
            reservation.unit(),
            reservation.intervalStart(),
            reservation.intervalEnd(),
            at);
    return projection.quantity().orElseThrow().compareTo(held) < 0;
  }

  private void recordLifecycleEvent(
      ExecutionContext context,
      CapacityReservationStore.LifecycleCandidate reservation,
      EventType eventType,
      Instant occurredAt) {
    var payload = objectMapper.createObjectNode();
    payload.put("transferReference", reservation.transferId().toString());
    payload.put("reservationReference", reservation.reservationId().toString());
    if (eventType.equals(EventType.parse("capacity.reservation-expired.v1"))) {
      payload.put("expiresAt", reservation.expiresAt().toString());
    } else {
      CapacityEvidenceProjection projection =
          projector.project(
              reservation.facility(),
              reservation.dimensionCode(),
              configuration.currentDimension(reservation.dimensionCode()),
              configuration.currentPolicy(reservation.dimensionCode()),
              evidence.findForProjection(reservation.facility(), reservation.dimensionCode()),
              clock.instant());
      String evidenceVersion =
          projection.evidence().stream()
              .map(fact -> fact.id().toString())
              .sorted()
              .collect(java.util.stream.Collectors.joining(","));
      payload.put("evidenceVersion", evidenceVersion.isBlank() ? "UNKNOWN" : evidenceVersion);
      payload.put(
          "reasonCode",
          eventType.equals(EventType.parse("capacity.reservation-recovered.v1"))
              ? "CAPACITY_EVIDENCE_SUPPORTS_HOLD"
              : "CAPACITY_EVIDENCE_NO_LONGER_SUPPORTS_HOLD");
    }
    outboxWriter
        .getObject()
        .record(
            new EventEnvelope(
                InternalId.generate(),
                eventType,
                "capacity",
                new OccurredAt(occurredAt),
                new RecordedAt(clock.instant()),
                context,
                Optional.of(
                    new AggregatePosition(
                        "capacity-reservation/" + reservation.reservationId(),
                        reservation.sequence())),
                payload));
  }

  public CapacityReleaseResult release(ExecutionContext context, InternalId transferId) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(transferId, "Transfer id");
    requireFacilityScope(context);
    requireReservationAuthority(context, transferId);
    try {
      return Objects.requireNonNull(
          transaction.execute(
              status -> {
                Instant now = clock.instant();
                CapacityReservationStore.ReleaseResult result =
                    reservations.release(
                        transferId, context.scope().facility().orElseThrow().value(), now);
                if (result.outcome() == CapacityReservationStore.ReleaseOutcome.SCOPE_MISMATCH) {
                  throw problem(
                      HttpStatus.FORBIDDEN,
                      PlatformErrorCode.INVALID_EXECUTION_SCOPE,
                      "Capacity release requires the reservation's validated Facility scope.");
                }
                if (result.changed()) {
                  auditRelease(
                      context,
                      transferId,
                      "CAPACITY_RESERVATION_RELEASE",
                      result.outcome().name(),
                      result.outcome() == CapacityReservationStore.ReleaseOutcome.RELEASED);
                  if (result.outcome() == CapacityReservationStore.ReleaseOutcome.EXPIRED) {
                    recordExpiryEvent(context, transferId, result, now);
                  }
                }
                return new CapacityReleaseResult(
                    CapacityReleaseResult.Outcome.valueOf(result.outcome().name()),
                    result.reservationId(),
                    result.sequence());
              }),
          "Capacity release result");
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Capacity release and required audit evidence could not be committed.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.CAPACITY_UNAVAILABLE,
          "Capacity could not confirm reservation release safely.");
    } catch (CapacityReservationStore.AmbiguousReleaseException ambiguous) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.CAPACITY_RESERVATION_CONFLICT,
          "More than one active Capacity reservation exists; identify the reservation operation explicitly.");
    }
  }

  /** Releases exactly one hold under a stable owner operation identity. */
  public CapacityReleaseResult release(
      ExecutionContext context,
      InternalId releaseOperationId,
      InternalId reservationOperationId,
      InternalId transferId) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(releaseOperationId, "release operation id");
    Objects.requireNonNull(reservationOperationId, "reservation operation id");
    Objects.requireNonNull(transferId, "Transfer id");
    requireFacilityScope(context);
    requireReservationAuthority(context, releaseOperationId);
    RequestFingerprint fingerprint =
        releaseFingerprint(reservationOperationId, transferId, context);
    try {
      return Objects.requireNonNull(
          transaction.execute(
              status -> {
                Instant now = clock.instant();
                CapacityReservationStore.ReleaseOperationClaim claim =
                    reservations.lockReleaseOperation(
                        releaseOperationId, reservationOperationId, transferId, fingerprint, now);
                if (!claim.fingerprintMatches()) {
                  throw problem(
                      HttpStatus.CONFLICT,
                      PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                      "The Capacity release operation identifier is already bound to different inputs.");
                }
                if (claim.current().isPresent()) {
                  CapacityReservationStore.ReleaseResult replay = claim.current().orElseThrow();
                  return new CapacityReleaseResult(
                      CapacityReleaseResult.Outcome.valueOf(replay.outcome().name()),
                      replay.reservationId(),
                      replay.sequence());
                }
                CapacityReservationStore.ReleaseResult result =
                    reservations.releaseReservation(
                        reservationOperationId,
                        transferId,
                        context.scope().facility().orElseThrow().value(),
                        now);
                if (result.outcome() == CapacityReservationStore.ReleaseOutcome.SCOPE_MISMATCH) {
                  throw problem(
                      HttpStatus.FORBIDDEN,
                      PlatformErrorCode.INVALID_EXECUTION_SCOPE,
                      "Capacity release requires the reservation's validated Facility scope.");
                }
                reservations.completeReleaseOperation(releaseOperationId, result, now);
                if (result.changed()) {
                  auditRelease(
                      context,
                      releaseOperationId,
                      "CAPACITY_RESERVATION_RELEASE",
                      result.outcome().name(),
                      result.outcome() == CapacityReservationStore.ReleaseOutcome.RELEASED);
                  if (result.outcome() == CapacityReservationStore.ReleaseOutcome.EXPIRED) {
                    recordExpiryEvent(context, transferId, result, now);
                  }
                }
                return new CapacityReleaseResult(
                    CapacityReleaseResult.Outcome.valueOf(result.outcome().name()),
                    result.reservationId(),
                    result.sequence());
              }),
          "Capacity operation-scoped release result");
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Capacity release and required audit evidence could not be committed.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.CAPACITY_UNAVAILABLE,
          "Capacity could not confirm reservation release safely.");
    }
  }

  private RequestFingerprint releaseFingerprint(
      InternalId reservationOperationId, InternalId transferId, ExecutionContext context) {
    var json = objectMapper.createObjectNode();
    json.put("reservationOperationId", reservationOperationId.toString());
    json.put("transferId", transferId.toString());
    json.put("facility", context.scope().facility().orElseThrow().value());
    return RequestFingerprint.from(json);
  }

  private void recordExpiryEvent(
      ExecutionContext context,
      InternalId transferId,
      CapacityReservationStore.ReleaseResult result,
      Instant recordedAt) {
    var payload = objectMapper.createObjectNode();
    payload.put("transferReference", transferId.toString());
    payload.put("reservationReference", result.reservationId().orElseThrow().toString());
    result.expiresAt().ifPresent(value -> payload.put("expiresAt", value.toString()));
    outboxWriter
        .getObject()
        .record(
            new EventEnvelope(
                InternalId.generate(),
                EventType.parse("capacity.reservation-expired.v1"),
                "capacity",
                new OccurredAt(result.expiresAt().orElse(recordedAt)),
                new RecordedAt(recordedAt),
                context,
                Optional.of(
                    new AggregatePosition(
                        "capacity-reservation/" + result.reservationId().orElseThrow(),
                        result.sequence())),
                payload));
  }

  private void auditRelease(
      ExecutionContext context,
      InternalId transferId,
      String action,
      String reason,
      boolean succeeded) {
    auditWriter
        .getObject()
        .recordMutation(
            new AuditEvidence(
                AuditActionKind.PROTECTED_MUTATION,
                Optional.of(context.actor()),
                Optional.of(context.scope()),
                action,
                "CapacityReservation",
                Optional.of(transferId),
                clock.instant(),
                succeeded ? AuditOutcome.SUCCEEDED : AuditOutcome.FAILED,
                Optional.of(reason),
                authenticationEvidence(context.authenticationMechanism()),
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId(),
                Optional.of(
                    new AuditTargetContext(
                        context.scope().kind(),
                        context.scope().organization(),
                        context.scope().facility()))));
  }

  public CapacityReservationResult reserve(
      ExecutionContext context, CapacityReservationRequest request, String idempotencyKey) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(request, "reservation request");
    requireFacilityScope(context, request);
    requireReservationAuthority(context, request.operationId());
    RequestFingerprint fingerprint = fingerprint(request);
    CommandReceiptKey receiptKey = receiptKey(context, request, idempotencyKey);
    try {
      return Objects.requireNonNull(
          transaction.execute(
              status -> reserveInTransaction(context, request, receiptKey, fingerprint)),
          "Capacity reservation result");
    } catch (CannotAcquireLockException busy) {
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DuplicateKeyException conflict) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.CAPACITY_RESERVATION_CONFLICT,
          "A Capacity reservation operation is already bound to this Transfer.");
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The reservation outcome and its required audit evidence could not be committed.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.CAPACITY_UNAVAILABLE,
          "Capacity could not evaluate or commit the reservation safely.");
    }
  }

  /** Returns the current owner lifecycle for a reservation without replaying a stale result. */
  public CapacityReservationOutcome getReservationOutcome(
      ExecutionContext context, InternalId operationId, InternalId transferId) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(transferId, "Transfer id");
    requireFacilityScope(context);
    requireReservationAuthority(context, operationId);
    try {
      CapacityReservationOutcome outcome =
          reservations.findReservationOutcome(
              operationId,
              transferId,
              context.scope().facility().orElseThrow().value(),
              clock.instant());
      auditReservationOutcomeRead(context, operationId, outcome.status());
      return outcome;
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Capacity reservation outcome and required read audit could not be recorded.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.CAPACITY_UNAVAILABLE,
          "Capacity could not confirm the reservation lifecycle safely.");
    }
  }

  private void auditReservationOutcomeRead(
      ExecutionContext context, InternalId operationId, CapacityReservationOutcome.Status status) {
    auditWriter
        .getObject()
        .recordNonMutating(
            new AuditEvidence(
                AuditActionKind.SENSITIVE_READ,
                Optional.of(context.actor()),
                Optional.of(context.scope()),
                "CAPACITY_RESERVATION_OUTCOME_READ",
                "CapacityReservationOperation",
                Optional.of(operationId),
                clock.instant(),
                AuditOutcome.SUCCEEDED,
                Optional.of("CURRENT_LIFECYCLE_" + status.name()),
                authenticationEvidence(context.authenticationMechanism()),
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId(),
                Optional.of(
                    new AuditTargetContext(
                        context.scope().kind(),
                        context.scope().organization(),
                        context.scope().facility()))));
  }

  private CapacityReservationResult reserveInTransaction(
      ExecutionContext context,
      CapacityReservationRequest request,
      CommandReceiptKey receiptKey,
      RequestFingerprint fingerprint) {
    Instant now = clock.instant();
    configuration.configureBoundedReceiptWait();
    CapacityReservationStore.ReceiptClaim receipt =
        reservations.claimReceipt(receiptKey, fingerprint, request.operationId(), now);
    if (receipt.state() == CapacityReservationStore.ReceiptState.KEY_REUSED) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
          "The reservation idempotency key was already used for different inputs.");
    }
    if (receipt.state() == CapacityReservationStore.ReceiptState.IN_PROGRESS) {
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    }
    if (receipt.state() == CapacityReservationStore.ReceiptState.REPLAY) {
      return receipt
          .result()
          .orElseThrow(
              () -> new IllegalStateException("Capacity reservation receipt has no outcome"));
    }

    CapacityReservationStore.OperationClaim operation =
        reservations.lockOperation(request.operationId(), request.transferId(), fingerprint, now);
    if (!operation.fingerprintMatches()) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
          "The reservation operation identifier is already bound to different inputs.");
    }
    Optional<CapacityReservationResult> prior = operation.current();
    if (prior.isPresent()) {
      CapacityReservationResult result = prior.orElseThrow();
      completeReceipt(receiptKey, fingerprint, result, now);
      return result;
    }

    if (!request.expiresAt().isAfter(now)) {
      CapacityReservationResult result =
          recordNonPositive(
              context,
              request,
              now,
              CapacityReservationResult.Outcome.INVALID,
              "RESERVATION_EXPIRY_NOT_FUTURE");
      completeReceipt(receiptKey, fingerprint, result, now);
      return result;
    }

    CapacityReservationResult result = evaluateAndRecord(context, request, now);
    completeReceipt(receiptKey, fingerprint, result, now);
    return result;
  }

  private CapacityReservationResult evaluateAndRecord(
      ExecutionContext context, CapacityReservationRequest request, Instant now) {
    var dimension = configuration.lockCurrentDimension(request.dimensionCode());
    if (dimension.isEmpty()) {
      return recordNonPositive(
          context, request, now, CapacityReservationResult.Outcome.UNKNOWN, "DIMENSION_UNKNOWN");
    }
    var configuredDimension = dimension.orElseThrow();
    if (!configuredDimension.reservable()
        || configuredDimension.valueKind() != CapacityValueKind.QUANTITY
        || !configuredDimension.unit().equals(request.unit())) {
      return recordNonPositive(
          context,
          request,
          now,
          CapacityReservationResult.Outcome.INVALID,
          "RESERVATION_DIMENSION_MISMATCH");
    }
    var policy = configuration.lockCurrentPolicy(request.dimensionCode());
    if (policy.isEmpty()
        || policy.orElseThrow().dimensionVersion() != configuredDimension.version()) {
      return recordNonPositive(
          context, request, now, CapacityReservationResult.Outcome.UNKNOWN, "POLICY_UNCONFIGURED");
    }

    List<CapacityObservationFact> facts =
        evidence.findForProjection(request.facility(), request.dimensionCode());
    CapacityEvidenceProjection projection =
        projector.project(
            request.facility(), request.dimensionCode(), dimension, policy, facts, now);
    if (projection.freshness() != CapacityEvidenceFreshness.CURRENT
        || projection.valueKind().filter(kind -> kind == CapacityValueKind.QUANTITY).isEmpty()
        || projection.quantity().isEmpty()
        || projection.unit().filter(request.unit()::equals).isEmpty()) {
      String reason =
          projection.reasonCodes().stream().findFirst().orElse("CAPACITY_EVIDENCE_NOT_CURRENT");
      return recordNonPositive(
          context, request, now, CapacityReservationResult.Outcome.UNKNOWN, reason);
    }

    BigDecimal held =
        reservations.sumActiveReservations(
            request.facility().issuer().value(),
            request.facility().value(),
            request.dimensionCode(),
            request.unit(),
            request.intervalStart(),
            request.intervalEnd(),
            now);
    BigDecimal unheld = projection.quantity().orElseThrow().subtract(held);
    if (unheld.compareTo(request.quantity()) < 0) {
      return recordNonPositive(
          context,
          request,
          now,
          CapacityReservationResult.Outcome.CONFLICT,
          "INSUFFICIENT_UNHELD_QUANTITY");
    }

    Instant reservationAt = clock.instant();
    if (!request.expiresAt().isAfter(reservationAt)) {
      return recordNonPositive(
          context,
          request,
          reservationAt,
          CapacityReservationResult.Outcome.INVALID,
          "RESERVATION_EXPIRY_NOT_FUTURE");
    }

    InternalId reservationId = InternalId.generate();
    reservations.createReservation(
        request,
        configuredDimension.version(),
        policy.orElseThrow().version(),
        projection.evidence().stream().map(item -> item.id()).toList(),
        reservationId,
        reservationAt);
    CapacityReservationResult result =
        new CapacityReservationResult(
            request.operationId(),
            CapacityReservationResult.Outcome.RESERVED,
            Optional.of(reservationId),
            Optional.empty(),
            false);
    reservations.recordOutcome(
        request.operationId(),
        result.outcome(),
        result.reservationId(),
        result.reasonCode(),
        reservationAt);
    audit(context, request, reservationId, "CAPACITY_RESERVATION_CREATE", "RESERVED", true);
    return result;
  }

  private CapacityReservationResult recordNonPositive(
      ExecutionContext context,
      CapacityReservationRequest request,
      Instant now,
      CapacityReservationResult.Outcome outcome,
      String reasonCode) {
    CapacityReservationResult result =
        new CapacityReservationResult(
            request.operationId(), outcome, Optional.empty(), Optional.of(reasonCode), false);
    reservations.recordOutcome(
        request.operationId(), result.outcome(), result.reservationId(), result.reasonCode(), now);
    audit(
        context, request, request.operationId(), "CAPACITY_RESERVATION_CREATE", reasonCode, false);
    return result;
  }

  private void audit(
      ExecutionContext context,
      CapacityReservationRequest request,
      InternalId target,
      String action,
      String reason,
      boolean succeeded) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              new AuditEvidence(
                  AuditActionKind.PROTECTED_MUTATION,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  action,
                  "CapacityReservation",
                  Optional.of(target),
                  clock.instant(),
                  succeeded ? AuditOutcome.SUCCEEDED : AuditOutcome.FAILED,
                  Optional.of(reason),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  Optional.of(
                      new AuditTargetContext(
                          context.scope().kind(),
                          context.scope().organization(),
                          context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw unavailable;
    }
  }

  private void requireReservationAuthority(ExecutionContext context, InternalId operationId) {
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, CapacityAuthorities.reservation().code());
    if (decision.isAllowed()) {
      return;
    }
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  "CAPACITY_RESERVATION_DENIED",
                  "CapacityReservationOperation",
                  Optional.of(operationId),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of(unavailable ? "AUTHORIZATION_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId()));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The reservation remains denied; its audit outcome is unavailable.");
    }
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Capacity reservation authority could not be evaluated safely."
            : "The actor is not authorized to reserve coordination Capacity at this Facility.");
  }

  private static void requireFacilityScope(
      ExecutionContext context, CapacityReservationRequest request) {
    if (context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()
        || !DIRECTORY_ISSUER.equals(context.scope().organization().orElseThrow().issuer().value())
        || !DIRECTORY_ISSUER.equals(context.scope().facility().orElseThrow().issuer().value())
        || !context.scope().facility().orElseThrow().equals(request.facility())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Capacity reservation requires the matching validated Directory Facility scope.");
    }
    InternalId.parse(context.scope().organization().orElseThrow().value());
    InternalId.parse(context.scope().facility().orElseThrow().value());
  }

  private static void requireFacilityScope(ExecutionContext context) {
    if (context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()
        || !DIRECTORY_ISSUER.equals(context.scope().organization().orElseThrow().issuer().value())
        || !DIRECTORY_ISSUER.equals(context.scope().facility().orElseThrow().issuer().value())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Capacity release requires a validated Directory Facility scope.");
    }
    InternalId.parse(context.scope().organization().orElseThrow().value());
    InternalId.parse(context.scope().facility().orElseThrow().value());
  }

  private void completeReceipt(
      CommandReceiptKey receiptKey,
      RequestFingerprint fingerprint,
      CapacityReservationResult result,
      Instant recordedAt) {
    reservations.completeReceipt(receiptKey, fingerprint, result, recordedAt);
  }

  private RequestFingerprint fingerprint(CapacityReservationRequest request) {
    var json = objectMapper.createObjectNode();
    json.put("operationId", request.operationId().toString());
    json.put("transferId", request.transferId().toString());
    json.put("facilityIssuer", request.facility().issuer().value());
    json.put("facilityId", request.facility().value());
    json.put("dimensionCode", request.dimensionCode());
    json.put("unit", request.unit());
    json.put("quantity", request.quantity());
    json.put("intervalStart", request.intervalStart().toString());
    json.put("intervalEnd", request.intervalEnd().toString());
    json.put("expiresAt", request.expiresAt().toString());
    return RequestFingerprint.from(json);
  }

  private static CommandReceiptKey receiptKey(
      ExecutionContext context, CapacityReservationRequest request, String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context,
          "capacity.reservation.create",
          "transfers/" + request.transferId(),
          idempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.CAPACITY_VALUE_INVALID,
          "A valid Idempotency-Key is required for the Capacity reservation.");
    }
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

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
