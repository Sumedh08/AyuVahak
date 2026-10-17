package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.matching.application.FederatedCapacityPeerPolicy;
import org.ayuvahak.matching.application.RecommendationApplicabilityResult;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
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
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Origin Transfer command that persists one ordered peer attempt before any network exchange. */
@Service
@Lazy
public final class FederatedReceivingOriginCommands {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final AllowedOperation RECEIVING_REQUEST =
      AllowedOperation.transferRemoteReceivingRequest(Direction.OUTBOUND);
  private static final EventType ATTEMPT_CHANGED =
      EventType.parse("transfer.federated-origin-receiving-attempt-changed.v1");
  private static final String BUSY_DETAIL =
      "A Transfer destination operation is still being processed; retry with the same idempotency key.";

  private final TransferStore transfers;
  private final FederatedOriginReceivingAttemptStore attempts;
  private final MatchingApplicabilityPort matching;
  private final FederatedCapacityPeerPolicy peers;
  private final org.ayuvahak.matching.application.FederationLocalDeploymentIdentity
      deploymentIdentity;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final OutboxWriter outbox;
  private final FederatedReceivingOriginOperationCoordinator coordinator;
  private final ObjectMapper mapper;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public FederatedReceivingOriginCommands(
      @Lazy TransferStore transfers,
      @Lazy FederatedOriginReceivingAttemptStore attempts,
      MatchingApplicabilityPort matching,
      FederatedCapacityPeerPolicy peers,
      org.ayuvahak.matching.application.FederationLocalDeploymentIdentity deploymentIdentity,
      AuthorizationService authorization,
      AuditWriter audit,
      OutboxWriter outbox,
      FederatedReceivingOriginOperationCoordinator coordinator,
      ObjectMapper mapper,
      @Lazy PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.transfers = Objects.requireNonNull(transfers, "Transfer store");
    this.attempts = Objects.requireNonNull(attempts, "origin attempt store");
    this.matching = Objects.requireNonNull(matching, "Matching applicability port");
    this.peers = Objects.requireNonNull(peers, "configured federation peers");
    this.deploymentIdentity = Objects.requireNonNull(deploymentIdentity, "deployment identity");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.outbox = Objects.requireNonNull(outbox, "outbox writer");
    this.coordinator = Objects.requireNonNull(coordinator, "origin operation coordinator");
    this.mapper = Objects.requireNonNull(mapper, "JSON mapper");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  /**
   * Creates or recovers an authorized destination attempt. The caller cannot select a peer URL or
   * operation ID; both identities are resolved and durably recorded by this owner command.
   */
  public FederatedOriginReceivingAttemptResult request(
      ExecutionContext context,
      InternalId journeyId,
      FederatedReceivingOriginCommand command,
      String idempotencyKey) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(journeyId, "TransferJourney id");
    Objects.requireNonNull(command, "federated receiving command");
    requireOriginScope(context.scope());
    requireAuthority(context, journeyId);
    RequestFingerprint commandFingerprint = commandFingerprint(command);
    CommandReceiptKey receiptKey = receiptKey(context, journeyId, idempotencyKey);
    TransferJourney observed =
        transfers.findJourney(journeyId).orElseThrow(FederatedReceivingOriginCommands::notFound);
    requireOriginTarget(context, observed);
    try {
      Creation creation =
          Objects.requireNonNull(
              transaction.execute(
                  ignored ->
                      createOrRecover(context, journeyId, command, commandFingerprint, receiptKey)),
              "origin receiving creation result");
      FederatedOriginReceivingAttempt coordinated =
          coordinator.submit(creation.attempt().operationId());
      return new FederatedOriginReceivingAttemptResult(coordinated, creation.replayed());
    } catch (CannotAcquireLockException busy) {
      throw problem(HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
    } catch (DuplicateKeyException conflict) {
      throw activeAttemptExists();
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The federated destination attempt and its required audit evidence could not be committed.");
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private Creation createOrRecover(
      ExecutionContext context,
      InternalId journeyId,
      FederatedReceivingOriginCommand command,
      RequestFingerprint fingerprint,
      CommandReceiptKey receiptKey) {
    Instant now = clock.instant();
    transfers.configureBoundedReceiptWait();
    TransferStore.ReceiptClaim claim = attempts.claimReceipt(receiptKey, fingerprint, now);
    switch (claim.state()) {
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The Idempotency-Key was already used for a different federated destination command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, BUSY_DETAIL);
      case REPLAY -> {
        InternalId attemptId = claim.resultReference().orElseThrow();
        FederatedOriginReceivingAttempt existing =
            attempts.findById(attemptId).orElseThrow(FederatedReceivingOriginCommands::unavailable);
        return new Creation(existing, true);
      }
      case OWNER -> {}
    }

    TransferJourney observed =
        transfers.findJourney(journeyId).orElseThrow(FederatedReceivingOriginCommands::notFound);
    transfers.lockReferralCoordination(observed.referralId());
    TransferJourney journey =
        transfers.lockJourney(journeyId).orElseThrow(FederatedReceivingOriginCommands::notFound);
    requireOriginTarget(context, journey);
    if (journey.version() != command.expectedJourneyVersion()) {
      throw stateConflict();
    }
    if (transfers.findJourneyClosure(journeyId).isPresent()) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_STATE_CONFLICT,
          "The TransferJourney has been closed and cannot start a federated destination attempt.");
    }
    requireLatestLocalAttemptReconciled(journeyId);
    if (transfers.hasBlockingFederatedDestinationAttempt(journeyId)) {
      throw activeAttemptExists();
    }
    requireRecommendationApplicable(context, journey, command);
    if (journey.originFacility().isEmpty()) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "Federated receiving requires a configured origin Facility reference.");
    }
    RemoteFacilityReference destinationFacility = command.destinationFacility();
    var peer =
        peers
            .resolve(destinationFacility, command.capacityDimensionCode())
            .orElseThrow(FederatedReceivingOriginCommands::peerUnavailable);
    if (!peer.peerDeploymentId().value().equals(destinationFacility.deploymentId().value())) {
      throw peerUnavailable();
    }
    DeploymentId originDeployment =
        deploymentIdentity.current().orElseThrow(FederatedReceivingOriginCommands::peerUnavailable);
    if (originDeployment.equals(peer.peerDeploymentId())) {
      throw peerUnavailable();
    }
    byte[] body = encodeRequest(originDeployment, journey, command);
    ExternalReference scope = destinationFacility.toExternalReference();
    FederationOperationFingerprint requestFingerprint =
        FederationOperationFingerprint.calculate(
            originDeployment, peer.peerDeploymentId(), scope, RECEIVING_REQUEST, body);
    int attemptNumber = Math.addExact(attempts.latestAttemptNumber(journeyId), 1);
    FederatedOriginReceivingAttempt attempt =
        FederatedOriginReceivingAttempt.pending(
            InternalId.generate(),
            journeyId,
            attemptNumber,
            originDeployment,
            peer.peerDeploymentId(),
            peer.partnerKey(),
            peer.configurationVersion(),
            InternalId.generate(),
            requestFingerprint,
            fingerprint.value(),
            body,
            now);
    if (!transfers.advanceJourneyVersion(journeyId, command.expectedJourneyVersion())) {
      throw stateConflict();
    }
    attempts.insert(attempt);
    auditMutation(context, attempt);
    recordChanged(context, attempt, now);
    attempts.completeReceipt(
        receiptKey, HttpStatus.CREATED.value(), attempt.id(), attempt.attemptNumber(), now);
    return new Creation(attempt, false);
  }

  private void requireLatestLocalAttemptReconciled(InternalId journeyId) {
    TransferRequestAttempt latest =
        transfers
            .findLatestRequest(journeyId)
            .orElseThrow(FederatedReceivingOriginCommands::unavailable);
    if (latest.status() == TransferRequestAttempt.Status.PENDING) {
      throw activeAttemptExists();
    }
    if (latest.status() != TransferRequestAttempt.Status.ACCEPTED) {
      return;
    }
    TransferStore.TransferAttemptState state =
        transfers
            .findAttemptState(latest.id())
            .orElseThrow(FederatedReceivingOriginCommands::unavailable);
    if (state.transferStatus().orElse(null) != CoordinatedTransfer.Status.CANCELLED
        || state
            .reservationOutcome()
            .filter(
                outcome ->
                    outcome
                            != org.ayuvahak.transfer.domain.CapacityReservationIntent.Outcome
                                .CONFLICT
                        && outcome
                            != org.ayuvahak.transfer.domain.CapacityReservationIntent.Outcome
                                .INVALID
                        && outcome
                            != org.ayuvahak.transfer.domain.CapacityReservationIntent.Outcome
                                .UNCONFIGURED
                        && outcome
                            != org.ayuvahak.transfer.domain.CapacityReservationIntent.Outcome
                                .RELEASED
                        && outcome
                            != org.ayuvahak.transfer.domain.CapacityReservationIntent.Outcome
                                .EXPIRED
                        && outcome
                            != org.ayuvahak.transfer.domain.CapacityReservationIntent.Outcome
                                .NOT_RESERVED)
            .isPresent()) {
      throw activeAttemptExists();
    }
  }

  private void requireRecommendationApplicable(
      ExecutionContext context, TransferJourney journey, FederatedReceivingOriginCommand command) {
    RecommendationApplicabilityResult result;
    try {
      result =
          matching.revalidate(
              context,
              journey.referralId(),
              command.recommendationId(),
              command.destinationFacility().toExternalReference(),
              command.referralRevision());
    } catch (RuntimeException unavailable) {
      throw matchingUnavailable();
    }
    if (result == null
        || !result.recommendationId().equals(command.recommendationId())
        || !result
            .candidateFacility()
            .equals(command.destinationFacility().toExternalReference())) {
      throw matchingUnavailable();
    }
    if (result.status() == RecommendationApplicabilityResult.Status.NOT_APPLICABLE) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.TRANSFER_RECOMMENDATION_STALE,
          "The recommendation no longer applies to this Referral revision and remote destination.");
    }
    if (result.status() != RecommendationApplicabilityResult.Status.APPLICABLE) {
      throw matchingUnavailable();
    }
  }

  private byte[] encodeRequest(
      DeploymentId originDeployment,
      TransferJourney journey,
      FederatedReceivingOriginCommand command) {
    ObjectNode body = mapper.createObjectNode();
    body.put("protocolProfile", "AyuVahak-Federation-v1");
    body.put("sourceDeploymentId", originDeployment.value());
    body.put("originContext", "transfer");
    body.put("originResourceType", "TransferJourney");
    body.put("originJourneyId", journey.id().toString());
    body.put("originFacilityId", journey.originFacility().orElseThrow().value());
    putReference(body, "destinationOrganization", command.destinationOrganization());
    putReference(body, "destinationFacility", command.destinationFacility().toExternalReference());
    body.put("capacityDimensionCode", command.capacityDimensionCode());
    body.put("capacityUnit", command.capacityUnit());
    body.put("capacityQuantity", command.capacityQuantity());
    body.put("capacityIntervalStart", command.capacityIntervalStart().toString());
    body.put("capacityIntervalEnd", command.capacityIntervalEnd().toString());
    try {
      return mapper.writeValueAsBytes(body);
    } catch (JacksonException failure) {
      throw unavailable();
    }
  }

  private static void putReference(ObjectNode body, String name, ExternalReference reference) {
    ObjectNode value = body.putObject(name);
    value.put("issuer", reference.issuer().value());
    value.put("resourceType", reference.resourceType());
    value.put("value", reference.value());
  }

  private static RequestFingerprint commandFingerprint(FederatedReceivingOriginCommand command) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode normalized = mapper.createObjectNode();
    normalized.put("expectedJourneyVersion", command.expectedJourneyVersion());
    normalized.put("referralRevision", command.referralRevision());
    normalized.put("recommendationId", command.recommendationId().toString());
    normalized.put(
        "destinationOrganizationIssuer", command.destinationOrganization().issuer().value());
    normalized.put("destinationOrganizationValue", command.destinationOrganization().value());
    normalized.put("destinationDeployment", command.destinationFacility().deploymentId().value());
    normalized.put("destinationFacilityId", command.destinationFacility().resourceId().toString());
    normalized.put("capacityDimensionCode", command.capacityDimensionCode());
    normalized.put("capacityUnit", command.capacityUnit());
    normalized.put("capacityQuantity", command.capacityQuantity());
    normalized.put("capacityIntervalStart", command.capacityIntervalStart().toString());
    normalized.put("capacityIntervalEnd", command.capacityIntervalEnd().toString());
    return RequestFingerprint.from(normalized);
  }

  private void auditMutation(ExecutionContext context, FederatedOriginReceivingAttempt attempt) {
    try {
      audit.recordMutation(
          new AuditEvidence(
              AuditActionKind.PROTECTED_MUTATION,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              "TRANSFER_FEDERATED_RECEIVING_REQUEST_INITIATE",
              "TransferJourney",
              Optional.of(attempt.journeyId()),
              clock.instant(),
              AuditOutcome.SUCCEEDED,
              Optional.of("DESTINATION_ATTEMPT_" + attempt.attemptNumber()),
              authenticationEvidence(context.authenticationMechanism()),
              AuditAuthorizationOutcome.ALLOWED,
              context.correlationId(),
              Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The federated destination attempt and its required audit evidence could not be committed.");
    }
  }

  private void recordChanged(
      ExecutionContext context, FederatedOriginReceivingAttempt attempt, Instant now) {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("resourceType", "transfer-journey");
    payload.put("resourceId", attempt.journeyId().toString());
    payload.put("version", attempt.attemptNumber());
    outbox.record(
        new EventEnvelope(
            InternalId.generate(),
            ATTEMPT_CHANGED,
            "transfer",
            new org.ayuvahak.platform.core.OccurredAt(now),
            new org.ayuvahak.platform.core.RecordedAt(now),
            context,
            Optional.of(
                new AggregatePosition(
                    "transfer-journey/" + attempt.journeyId(), attempt.attemptNumber())),
            payload));
  }

  private void requireAuthority(ExecutionContext context, InternalId journeyId) {
    AuthorizationDecision decision =
        authorization.evaluate(context, TransferAuthorities.request().code());
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
              "TRANSFER_FEDERATED_RECEIVING_REQUEST_DENIED",
              "TransferJourney",
              Optional.of(journeyId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(unavailable ? "AUTHORIZATION_STORE_UNAVAILABLE" : "AUTHORIZATION_DENIED"),
              authenticationEvidence(context.authenticationMechanism()),
              AuditAuthorizationOutcome.DENIED,
              context.correlationId()));
    } catch (AuditPersistenceUnavailableException auditUnavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The federated request remains denied; its denial audit could not be committed.");
    }
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        unavailable
            ? "Transfer authorization is temporarily unavailable."
            : "The actor is not authorized to initiate a federated Transfer request.");
  }

  private static void requireOriginTarget(ExecutionContext context, TransferJourney journey) {
    if (!journey.originOrganization().equals(context.scope().organization().orElseThrow())
        || (context.scope().facility().isPresent()
            && !journey.originFacility().equals(context.scope().facility()))) {
      throw notFound();
    }
  }

  private static void requireOriginScope(ExecutionScope scope) {
    if (scope.kind() == ExecutionScope.Kind.PLATFORM
        || scope.organization().isEmpty()
        || !DIRECTORY_ISSUER.equals(scope.organization().orElseThrow().issuer().value())
        || scope
            .facility()
            .filter(reference -> !DIRECTORY_ISSUER.equals(reference.issuer().value()))
            .isPresent()) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Federated Transfer requests require a validated Directory origin scope.");
    }
  }

  private static CommandReceiptKey receiptKey(
      ExecutionContext context, InternalId journeyId, String idempotencyKey) {
    try {
      return CommandReceiptKey.from(
          context, "transfer.federated-receiving.create", "journeys/" + journeyId, idempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "A valid Idempotency-Key is required for this federated Transfer command.");
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

  private static AuditTargetContext targetContext(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
  }

  private static PlatformProblemException matchingUnavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_DESTINATION_NOT_ELIGIBLE,
        "Recommendation applicability could not be established safely.");
  }

  private static PlatformProblemException peerUnavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "The configured receiving deployment is unavailable or not approved for this operation.");
  }

  private static PlatformProblemException activeAttemptExists() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_ACTIVE_ATTEMPT_EXISTS,
        "The prior Transfer destination operation is not terminal and reconciled.");
  }

  private static PlatformProblemException stateConflict() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.TRANSFER_STATE_CONFLICT,
        "The TransferJourney changed since the caller last observed it.");
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.TRANSFER_REQUEST_NOT_FOUND,
        "The TransferJourney was not found in the authorized scope.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.TRANSFER_UNAVAILABLE,
        "Transfer could not complete the federated destination operation safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private record Creation(FederatedOriginReceivingAttempt attempt, boolean replayed) {}
}
