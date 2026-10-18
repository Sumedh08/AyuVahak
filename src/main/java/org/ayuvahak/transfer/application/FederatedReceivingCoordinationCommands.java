package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
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
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorization;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest.Assessment;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Transfer-owned commands for receiver-local federated coordination requests. */
@Service
@Lazy
public final class FederatedReceivingCoordinationCommands {

  private static final java.time.Duration MAX_FUTURE_SKEW = java.time.Duration.ofMinutes(5);

  private static final AllowedOperation RECEIVING_REQUEST =
      AllowedOperation.transferRemoteReceivingRequest(Direction.INBOUND);
  private static final AllowedOperation RESERVATION_STATUS =
      AllowedOperation.transferRemoteReservationStatus(Direction.INBOUND);
  private static final EventType RECEIVING_REQUEST_CHANGED =
      EventType.parse("transfer.federated-receiving-request-changed.v1");

  private final FederatedReceivingRequestStore store;
  private final DirectoryScopeReferenceLookup directory;
  private final AuthorizationService authorizationService;
  private final AuditWriter audit;
  private final FederatedReceivingCapacityRecovery capacityRecovery;
  private final ExecutionContextFactory contexts;
  private final OutboxWriter outboxWriter;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public FederatedReceivingCoordinationCommands(
      @Lazy FederatedReceivingRequestStore store,
      DirectoryScopeReferenceLookup directory,
      AuthorizationService authorizationService,
      AuditWriter audit,
      FederatedReceivingCapacityRecovery capacityRecovery,
      ExecutionContextFactory contexts,
      OutboxWriter outboxWriter,
      ObjectMapper objectMapper,
      @Lazy PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "federated receiving store");
    this.directory = Objects.requireNonNull(directory, "Directory scope lookup");
    this.authorizationService =
        Objects.requireNonNull(authorizationService, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.capacityRecovery = Objects.requireNonNull(capacityRecovery, "Capacity recovery");
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
    this.outboxWriter = Objects.requireNonNull(outboxWriter, "outbox writer");
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  /**
   * Accepts one named peer request after the exchange adapter has revalidated current peer trust.
   * The receiver creates a local case and receipt; no local Transfer/Journey/Referral is created.
   */
  public FederatedReceivingRequestResult receive(
      FederationPeerInboundAuthorization authorization,
      InternalId operationId,
      FederationOperationFingerprint fingerprint,
      FederatedReceivingRequestInput input) {
    Objects.requireNonNull(authorization, "peer authorization");
    Objects.requireNonNull(operationId, "operation id");
    Objects.requireNonNull(fingerprint, "operation fingerprint");
    Objects.requireNonNull(input, "receiving request");
    validatePeerOperation(authorization, input);
    if (!directory.isActiveFacilityInOrganization(
        input.destinationOrganization(), input.destinationFacility())) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_DESTINATION_NOT_ELIGIBLE,
          "The requested receiving Facility is not an active member of the named Organization.");
    }

    Instant now = clock.instant();
    ExecutionContext eventContext =
        contexts.forIntegrationRequest(
            authorization.authenticatedPeerPrincipal(),
            RequestedExecutionScope.facility(
                input.destinationOrganization(), input.destinationFacility()),
            CorrelationId.generate());
    FederatedReceivingRequest candidate =
        new FederatedReceivingRequest(
            InternalId.generate(),
            authorization.peerDeployment(),
            operationId,
            fingerprint.value(),
            input.originJourneyId(),
            input.originFacilityId(),
            input.destinationOrganization(),
            input.destinationFacility(),
            input.capacityDimensionCode(),
            input.capacityUnit(),
            input.capacityQuantity(),
            input.capacityIntervalStart(),
            input.capacityIntervalEnd(),
            FederatedReceivingRequest.Status.RECEIVED,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            FederatedReceivingRequest.ReservationStatus.NOT_REQUESTED,
            Optional.empty(),
            Optional.empty(),
            0,
            1,
            now,
            now);
    try {
      return Objects.requireNonNull(
          transaction.execute(
              ignored -> {
                FederatedReceivingRequestStore.Claim claim = store.receive(candidate, now);
                if (!claim.fingerprintMatches()) {
                  throw new FederatedOperationFingerprintConflict(claim.request().id());
                }
                if (claim.created()) {
                  audit.recordMutation(
                      new AuditEvidence(
                          AuditActionKind.PROTECTED_MUTATION,
                          Optional.of(authorization.authenticatedPeerPrincipal()),
                          Optional.empty(),
                          "TRANSFER_FEDERATED_RECEIVING_REQUEST_RECEIVE",
                          "FederatedReceivingRequest",
                          Optional.of(claim.request().id()),
                          now,
                          AuditOutcome.SUCCEEDED,
                          Optional.empty(),
                          AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN,
                          AuditAuthorizationOutcome.ALLOWED,
                          eventContext.correlationId()));
                  recordChanged(eventContext, claim.request(), now, now);
                }
                return new FederatedReceivingRequestResult(claim.request(), !claim.created());
              }),
          "federated receiving transaction result");
    } catch (FederatedOperationFingerprintConflict conflict) {
      try {
        audit.recordNonMutating(
            new AuditEvidence(
                AuditActionKind.SECURITY_OUTCOME,
                Optional.of(authorization.authenticatedPeerPrincipal()),
                Optional.of(eventContext.scope()),
                "TRANSFER_FEDERATED_RECEIVING_REPLAY_CONFLICT",
                "FederatedReceivingRequest",
                Optional.of(conflict.requestId()),
                now,
                AuditOutcome.DENIED,
                Optional.of("OPERATION_FINGERPRINT_CONFLICT"),
                AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN,
                AuditAuthorizationOutcome.DENIED,
                eventContext.correlationId(),
                Optional.of(targetContext(eventContext.scope()))));
      } catch (AuditPersistenceUnavailableException unavailable) {
        throw problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            PlatformErrorCode.AUDIT_UNAVAILABLE,
            "The replay conflict remains denied; its required audit record is unavailable.");
      }
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.EXCHANGE_REPLAY_CONFLICT,
          "The peer reused a receiving operation identity with different content.");
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The receiving request and its required audit evidence could not be committed.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSFER_UNAVAILABLE,
          "The receiving request could not be committed safely.");
    }
  }

  /** Records one attributed local assessment against the current receiver-owned case version. */
  public FederatedReceivingRequest recordAssessment(
      ExecutionContext context,
      InternalId requestId,
      FederatedReceivingRequest.AssessmentKind kind,
      FederatedReceivingRequest.Outcome outcome,
      String reasonCode,
      Instant occurredAt,
      long expectedVersion) {
    Objects.requireNonNull(kind, "assessment kind");
    Objects.requireNonNull(outcome, "assessment outcome");
    Objects.requireNonNull(occurredAt, "assessment time");
    requireLocalFacilityContext(context);
    String authorityCode =
        kind == FederatedReceivingRequest.AssessmentKind.CLINICAL
            ? TransferAuthorities.clinicalSuitability().code()
            : TransferAuthorities.operationalReadiness().code();
    requireLocalAuthority(context, requestId, authorityCode, "TRANSFER_FEDERATED_ASSESSMENT");
    Instant now = clock.instant();
    requireNotFutureSkewed(occurredAt, now);
    try {
      return Objects.requireNonNull(
          transaction.execute(
              ignored -> {
                FederatedReceivingRequest current =
                    store
                        .lock(requestId)
                        .orElseThrow(FederatedReceivingCoordinationCommands::notFound);
                requireLocalDestination(context, current);
                if (current.status() != FederatedReceivingRequest.Status.RECEIVED
                    || current.version() != expectedVersion) {
                  throw stateConflict();
                }
                Assessment assessment =
                    new Assessment(kind, outcome, reasonCode, context.actor(), occurredAt, now);
                FederatedReceivingRequest updated = current.withAssessment(assessment);
                if (!store.update(updated, current.version())) {
                  throw stateConflict();
                }
                store.appendAssessment(requestId, assessment, updated.version());
                audit.recordMutation(
                    new AuditEvidence(
                        AuditActionKind.PROTECTED_MUTATION,
                        Optional.of(context.actor()),
                        Optional.of(context.scope()),
                        "TRANSFER_FEDERATED_" + kind.name() + "_ASSESSMENT",
                        "FederatedReceivingRequest",
                        Optional.of(requestId),
                        now,
                        AuditOutcome.SUCCEEDED,
                        Optional.of(outcome.name()),
                        authenticationEvidence(context),
                        AuditAuthorizationOutcome.ALLOWED,
                        context.correlationId(),
                        Optional.of(targetContext(context.scope()))));
                recordChanged(context, updated, occurredAt, now);
                return updated;
              }),
          "federated assessment result");
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The receiving assessment and its required audit evidence could not be committed.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSFER_UNAVAILABLE,
          "The receiving assessment could not be committed safely.");
    }
  }

  /**
   * Commits receiver-local Acceptance and a distinct Capacity operation before calling Capacity.
   */
  public FederatedReceivingRequest accept(
      ExecutionContext context, InternalId requestId, Instant occurredAt, long expectedVersion) {
    Objects.requireNonNull(occurredAt, "acceptance time");
    requireLocalFacilityContext(context);
    requireLocalAuthority(
        context,
        requestId,
        TransferAuthorities.acceptance().code(),
        "TRANSFER_FEDERATED_RECEIVING_ACCEPT");
    Instant now = clock.instant();
    requireNotFutureSkewed(occurredAt, now);
    FederatedReceivingRequest accepted;
    try {
      accepted =
          Objects.requireNonNull(
              transaction.execute(
                  ignored -> {
                    FederatedReceivingRequest current =
                        store
                            .lock(requestId)
                            .orElseThrow(FederatedReceivingCoordinationCommands::notFound);
                    requireLocalDestination(context, current);
                    if (current.status() != FederatedReceivingRequest.Status.RECEIVED
                        || current.version() != expectedVersion) {
                      throw stateConflict();
                    }
                    requireSupportingAssessment(
                        current, FederatedReceivingRequest.AssessmentKind.CLINICAL);
                    requireSupportingAssessment(
                        current, FederatedReceivingRequest.AssessmentKind.OPERATIONAL);
                    FederatedReceivingRequest updated =
                        current.accept(context.actor(), occurredAt, InternalId.generate(), now);
                    if (!store.update(updated, current.version())) {
                      throw stateConflict();
                    }
                    audit.recordMutation(
                        new AuditEvidence(
                            AuditActionKind.PROTECTED_MUTATION,
                            Optional.of(context.actor()),
                            Optional.of(context.scope()),
                            "TRANSFER_FEDERATED_RECEIVING_ACCEPT",
                            "FederatedReceivingRequest",
                            Optional.of(requestId),
                            now,
                            AuditOutcome.SUCCEEDED,
                            Optional.empty(),
                            authenticationEvidence(context),
                            AuditAuthorizationOutcome.ALLOWED,
                            context.correlationId(),
                            Optional.of(targetContext(context.scope()))));
                    recordChanged(context, updated, occurredAt, now);
                    return updated;
                  }),
              "federated acceptance result");
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Acceptance and its required audit evidence could not be committed.");
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSFER_UNAVAILABLE,
          "The receiving Acceptance could not be committed safely.");
    }
    // The owner transaction is committed before the Capacity port is called.
    return capacityRecovery.reconcile(accepted.id());
  }

  /** Reads one receiver-owned case under a fresh local Transfer view grant. */
  public FederatedReceivingRequest get(ExecutionContext context, InternalId requestId) {
    Objects.requireNonNull(requestId, "receiving request id");
    requireLocalFacilityContext(context);
    requireLocalAuthority(
        context,
        requestId,
        TransferAuthorities.view().code(),
        "TRANSFER_FEDERATED_RECEIVING_REQUEST_READ");
    FederatedReceivingRequest request =
        store.find(requestId).orElseThrow(FederatedReceivingCoordinationCommands::notFound);
    requireLocalDestination(context, request);
    Instant now = clock.instant();
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SENSITIVE_READ,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_FEDERATED_RECEIVING_REQUEST_READ",
              "FederatedReceivingRequest",
              Optional.of(requestId),
              now,
              AuditOutcome.SUCCEEDED,
              Optional.empty(),
              authenticationEvidence(context),
              AuditAuthorizationOutcome.ALLOWED,
              context.correlationId(),
              Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The receiving request cannot be read while required audit storage is unavailable.");
    }
    return request;
  }

  /** Revalidates peer scope and returns the same receiver-owned reservation operation outcome. */
  public FederatedReceivingRequestResult reservationStatus(
      FederationPeerInboundAuthorization authorization,
      InternalId requestOperationId,
      FederationOperationFingerprint requestFingerprint,
      org.ayuvahak.platform.core.ExternalReference requestedOrganization,
      org.ayuvahak.platform.core.ExternalReference requestedFacility) {
    Objects.requireNonNull(authorization, "peer authorization");
    Objects.requireNonNull(requestOperationId, "request operation id");
    Objects.requireNonNull(requestFingerprint, "original receiving request fingerprint");
    Objects.requireNonNull(requestedOrganization, "requested Organization scope");
    Objects.requireNonNull(requestedFacility, "requested Facility scope");
    if (!RESERVATION_STATUS.equals(authorization.operation())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "The peer is not authorized for this reservation status operation.");
    }
    FederatedReceivingRequest current =
        store
            .findByPeerOperation(authorization.peerDeployment(), requestOperationId)
            .orElseThrow(FederatedReceivingCoordinationCommands::notFound);
    if (!authorization.scope().equals(current.destinationFacility())
        || !requestedFacility.equals(current.destinationFacility())
        || !requestedOrganization.equals(current.destinationOrganization())) {
      ExecutionContext peerContext =
          peerContext(
              authorization, current.destinationOrganization(), current.destinationFacility());
      recordPeerStatusAudit(
          peerContext,
          current.id(),
          "TRANSFER_FEDERATED_RESERVATION_STATUS_SCOPE_DENIED",
          AuditOutcome.DENIED,
          Optional.of("PEER_RECEIVING_SCOPE_MISMATCH"));
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "The peer is not authorized for this receiving Facility scope.");
    }
    if (!current.requestFingerprint().equals(requestFingerprint.value())) {
      ExecutionContext peerContext =
          peerContext(authorization, requestedOrganization, requestedFacility);
      recordPeerStatusAudit(
          peerContext,
          current.id(),
          "TRANSFER_FEDERATED_RESERVATION_STATUS_REPLAY_CONFLICT",
          AuditOutcome.DENIED,
          Optional.of("OPERATION_FINGERPRINT_CONFLICT"));
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.EXCHANGE_REPLAY_CONFLICT,
          "The reservation status request does not match the original receiving operation.");
    }
    ExecutionContext peerContext =
        peerContext(authorization, requestedOrganization, requestedFacility);
    recordPeerStatusAudit(
        peerContext,
        current.id(),
        "TRANSFER_FEDERATED_RESERVATION_STATUS_READ",
        AuditOutcome.SUCCEEDED,
        Optional.empty());
    return new FederatedReceivingRequestResult(capacityRecovery.reconcile(current.id()), false);
  }

  private ExecutionContext peerContext(
      FederationPeerInboundAuthorization authorization,
      org.ayuvahak.platform.core.ExternalReference organization,
      org.ayuvahak.platform.core.ExternalReference facility) {
    return contexts.forIntegrationRequest(
        authorization.authenticatedPeerPrincipal(),
        RequestedExecutionScope.facility(organization, facility),
        CorrelationId.generate());
  }

  private void recordPeerStatusAudit(
      ExecutionContext context,
      InternalId requestId,
      String action,
      AuditOutcome outcome,
      Optional<String> reason) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              outcome == AuditOutcome.SUCCEEDED
                  ? AuditActionKind.SENSITIVE_READ
                  : AuditActionKind.SECURITY_OUTCOME,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              action,
              "FederatedReceivingRequest",
              Optional.of(requestId),
              clock.instant(),
              outcome,
              reason,
              AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN,
              outcome == AuditOutcome.SUCCEEDED
                  ? AuditAuthorizationOutcome.ALLOWED
                  : AuditAuthorizationOutcome.DENIED,
              context.correlationId(),
              Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The peer operation remains blocked because its required audit record is unavailable.");
    }
  }

  private void requireLocalAuthority(
      ExecutionContext context, InternalId requestId, String authorityCode, String action) {
    AuthorizationDecision decision = authorizationService.evaluate(context, authorityCode);
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
              action + "_DENIED",
              "FederatedReceivingRequest",
              Optional.of(requestId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
              authenticationEvidence(context),
              AuditAuthorizationOutcome.DENIED,
              context.correlationId(),
              Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The receiving operation remains denied; its required denial audit is unavailable.");
    }
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer authorization is temporarily unavailable."
            : "The actor is not authorized for this receiving function at this Facility.");
  }

  private static void requireLocalFacilityContext(ExecutionContext context) {
    if (context == null
        || context.actor().kind() != ActorKind.HUMAN
        || context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()
        || !"urn:ayuvahak:directory"
            .equals(context.scope().organization().orElseThrow().issuer().value())
        || !"urn:ayuvahak:directory"
            .equals(context.scope().facility().orElseThrow().issuer().value())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Receiving decisions require an authorized local Facility scope.");
    }
  }

  private static void requireNotFutureSkewed(Instant occurredAt, Instant now) {
    if (occurredAt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "The source-reported time exceeds the allowed future clock-skew bound.");
    }
  }

  private void requireLocalDestination(
      ExecutionContext context, FederatedReceivingRequest request) {
    if (!request.destinationFacility().equals(context.scope().facility().orElseThrow())
        || !request
            .destinationOrganization()
            .equals(context.scope().organization().orElseThrow())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.TRANSFER_SCOPE_MISMATCH,
          "The receiving request is outside the authorized local Facility scope.");
    }
    if (!directory.isActiveFacilityInOrganization(
        request.destinationOrganization(), request.destinationFacility())) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_DESTINATION_NOT_ELIGIBLE,
          "The receiving Facility is no longer active in the stated Organization.");
    }
  }

  private static void requireSupportingAssessment(
      FederatedReceivingRequest request, FederatedReceivingRequest.AssessmentKind kind) {
    Optional<Assessment> assessment =
        kind == FederatedReceivingRequest.AssessmentKind.CLINICAL
            ? request.clinicalAssessment()
            : request.operationalAssessment();
    if (assessment
        .filter(a -> a.outcome() == FederatedReceivingRequest.Outcome.SUPPORTS)
        .isEmpty()) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_ASSESSMENT_REQUIRED,
          "Acceptance requires positive clinical and operational receiving assessments.");
    }
  }

  private static AuditAuthenticationEvidence authenticationEvidence(ExecutionContext context) {
    return context.actor().kind() == ActorKind.HUMAN
        ? AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN
        : AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
  }

  private static AuditTargetContext targetContext(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
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

  private static void validatePeerOperation(
      FederationPeerInboundAuthorization authorization, FederatedReceivingRequestInput input) {
    if (!RECEIVING_REQUEST.equals(authorization.operation())
        || !"urn:ayuvahak:directory".equals(authorization.scope().issuer().value())
        || !"Facility".equals(authorization.scope().resourceType())
        || !authorization.scope().equals(input.destinationFacility())) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "The peer is not authorized for this receiving operation and Facility scope.");
    }
  }

  private static PlatformProblemException stateConflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_STATE_CONFLICT,
        "The receiving request changed; reload it and retry with its current version.");
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.TRANSFER_REQUEST_NOT_FOUND,
        "The federated receiving request was not found.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private static final class FederatedOperationFingerprintConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final InternalId requestId;

    private FederatedOperationFingerprintConflict(InternalId requestId) {
      super("Federated receiving operation fingerprint conflicts with its durable receipt.");
      this.requestId = Objects.requireNonNull(requestId, "requestId");
    }

    private InternalId requestId() {
      return requestId;
    }
  }
}
