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
import org.ayuvahak.capacity.domain.CapacityReservationRequest;
import org.ayuvahak.capacity.domain.CapacityReservationResult;
import org.ayuvahak.capacity.domain.CapacityValueKind;
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
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
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
  private final CapacityEvidenceProjector projector = new CapacityEvidenceProjector();

  public CapacityReservationCommands(
      @Lazy CapacityReservationStore reservations,
      @Lazy CapacityPolicyStore configuration,
      @Lazy CapacityEvidenceStore evidence,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock,
      ObjectMapper objectMapper) {
    this.reservations = Objects.requireNonNull(reservations, "reservation store");
    this.configuration = Objects.requireNonNull(configuration, "Capacity configuration");
    this.evidence = Objects.requireNonNull(evidence, "Capacity evidence");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
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
