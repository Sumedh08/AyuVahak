package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.capacity.domain.CapacityReleaseResult;
import org.ayuvahak.capacity.domain.CapacityReservationOutcome;
import org.ayuvahak.matching.application.RecommendationApplicabilityResult;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.audit.domain.AuditTargetContext;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.InternalEvent;
import org.ayuvahak.platform.eventing.application.InternalEventHandler;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.ReceivingAssessment;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation.ProviderOutcome;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Applies Transport's concrete acknowledgement only after Transfer revalidates its own intent. */
@Component
@Lazy
public final class TransferDestinationChangeEventHandler implements InternalEventHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(TransferDestinationChangeEventHandler.class);
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final EventType RESOLVED =
      EventType.parse("transport.destination-change-resolved.v1");

  private final TransferStore transfers;
  private final MatchingApplicabilityPort matching;
  private final CapacityReservationPort capacity;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final ExecutionContextFactory contexts;
  private final TransferProperties properties;
  private final AuthenticationProperties authentication;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final ObjectMapper mapper;

  public TransferDestinationChangeEventHandler(
      @Lazy TransferStore transfers,
      MatchingApplicabilityPort matching,
      CapacityReservationPort capacity,
      AuthorizationService authorization,
      AuditWriter audit,
      ExecutionContextFactory contexts,
      TransferProperties properties,
      AuthenticationProperties authentication,
      @Lazy PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock,
      ObjectMapper mapper) {
    this.transfers = transfers;
    this.matching = matching;
    this.capacity = capacity;
    this.authorization = authorization;
    this.audit = audit;
    this.contexts = contexts;
    this.properties = properties;
    this.authentication = authentication;
    this.transaction = new TransactionTemplate(transactionManager);
    this.clock = clock;
    this.mapper = mapper;
  }

  @Override
  public Set<EventType> supportedEventTypes() {
    return Set.of(RESOLVED);
  }

  @Override
  public void handle(InternalEvent event) throws Exception {
    try {
      handleEvent(event);
    } catch (Exception failure) {
      StackTraceElement location =
          failure.getStackTrace().length == 0 ? null : failure.getStackTrace()[0];
      Throwable root = failure;
      while (root.getCause() != null) root = root.getCause();
      String sqlState = root instanceof java.sql.SQLException sql ? sql.getSQLState() : "n/a";
      logger.warn(
          "Transfer destination-change event remains recoverable eventType={} failureType={} failureLocation={} sqlState={}",
          event.eventType(),
          failure.getClass().getSimpleName(),
          location == null
              ? "unknown"
              : location.getClassName()
                  + "."
                  + location.getMethodName()
                  + ":"
                  + location.getLineNumber(),
          sqlState);
      throw failure;
    }
  }

  private void handleEvent(InternalEvent event) throws Exception {
    if (transfers.isConsumerEventCompleted(event.eventId(), event.eventType())) {
      return;
    }
    JsonNode envelope = mapper.readTree(event.envelopeJson());
    JsonNode payload = object(envelope, "payload");
    InternalId transferId = id(payload, "transferReference");
    InternalId transportRequestId = id(payload, "transportRequestReference");
    InternalId transferOperationId = id(payload, "transferDestinationChangeReference");
    InternalId transportOperationId = id(payload, "transportDestinationChangeReference");
    ProviderOutcome outcome = ProviderOutcome.valueOf(text(payload, "outcome"));
    Instant occurredAt = Instant.parse(text(envelope, "occurredAt"));
    Instant recordedAt = clock.instant();

    TransferProgressTarget target =
        transfers.findProgressTarget(transferId).orElseThrow(TransferNotFound::new);
    ExecutionContext context = originContext(target, envelope, event.eventId());
    if (!hasOriginScope(envelope, target)) {
      throw new IllegalArgumentException(
          "Transport destination-change event has an invalid owner scope");
    }
    if (!authorization.evaluate(context, TransferAuthorities.progress().code()).isAllowed()) {
      auditDenied(context, transferId);
      throw new IllegalStateException(
          "Transfer destination-change event consumer is not authorized");
    }

    TransferDestinationChangeOperation initial =
        transfers.findDestinationChange(transferOperationId).orElseThrow(TransferNotFound::new);
    Optional<String> initialMismatch =
        linkageMismatch(initial, payload, transferId, transportRequestId, transportOperationId);
    if (initialMismatch.isPresent()) {
      reconcileUnlinkedEvent(event, transferId, context, recordedAt, initialMismatch.orElseThrow());
      return;
    }

    Revalidation revalidation =
        outcome == ProviderOutcome.ACCEPTED
            ? revalidateAcceptedOutcome(initial, target, context, event)
            : Revalidation.CURRENT;
    Resolution resolution =
        transaction.execute(
            ignored ->
                applyOutcome(
                    event,
                    payload,
                    transferId,
                    transportRequestId,
                    transportOperationId,
                    outcome,
                    revalidation,
                    context,
                    occurredAt,
                    clock.instant()));
    if (resolution.releasePending()) {
      attemptCapacityRelease(resolution.operation());
    }
  }

  /**
   * Retries stable Capacity release identities after process restart or an unavailable response.
   */
  public int recoverPendingCapacityReleases(int limit) {
    int processed = 0;
    for (InternalId operationId : transfers.findPendingDestinationChangeReleases(limit)) {
      try {
        TransferDestinationChangeOperation operation =
            transfers.findDestinationChange(operationId).orElseThrow(TransferNotFound::new);
        attemptCapacityRelease(operation);
        processed++;
      } catch (RuntimeException failure) {
        logger.warn(
            "Transfer destination-change Capacity cleanup deferred operation={} failureType={}",
            operationId,
            failure.getClass().getSimpleName());
      }
    }
    return processed;
  }

  private Revalidation revalidateAcceptedOutcome(
      TransferDestinationChangeOperation operation,
      TransferProgressTarget target,
      ExecutionContext context,
      InternalEvent event) {
    var journey =
        transfers.findJourney(target.transfer().journeyId()).orElseThrow(TransferNotFound::new);
    RecommendationApplicabilityResult applicability;
    try {
      applicability =
          matching.revalidate(
              context,
              journey.referralId(),
              operation.recommendationId(),
              operation.proposedFacility(),
              operation.referralRevision());
    } catch (RuntimeException unavailable) {
      throw new IllegalStateException(
          "Matching could not revalidate the proposed destination", unavailable);
    }
    if (applicability == null
        || !applicability.recommendationId().equals(operation.recommendationId())
        || !applicability.candidateFacility().equals(operation.proposedFacility())) {
      throw new IllegalStateException("Matching returned an ambiguous destination-change result");
    }
    if (applicability.status() == RecommendationApplicabilityResult.Status.NOT_APPLICABLE) {
      return Revalidation.STALE;
    }
    if (applicability.status() != RecommendationApplicabilityResult.Status.APPLICABLE) {
      throw new IllegalStateException(
          "Matching applicability is indeterminate for destination change");
    }
    if (!hasSupportingReceivingAssessments(operation.id())) {
      return Revalidation.STALE;
    }
    ExecutionContext capacityContext = capacityContext(operation, event);
    if (!authorization
        .evaluate(capacityContext, TransferAuthorities.reservationReconciliation().code())
        .isAllowed()) {
      throw new IllegalStateException(
          "Transfer Capacity reconciliation identity is not authorized");
    }
    CapacityReservationOutcome current;
    try {
      current =
          capacity.getReservationOutcome(
              capacityContext, operation.capacityReservationOperationId(), operation.transferId());
    } catch (RuntimeException unavailable) {
      throw new IllegalStateException(
          "Capacity could not revalidate the proposed reservation", unavailable);
    }
    if (!current.operationId().equals(operation.capacityReservationOperationId())
        || !current.transferId().equals(operation.transferId())) {
      throw new IllegalStateException("Capacity returned an ambiguous reservation reference");
    }
    if (current.status() != CapacityReservationOutcome.Status.RESERVED
        || current.expiresAt().filter(clock.instant()::isBefore).isEmpty()) {
      return Revalidation.STALE;
    }
    return Revalidation.CURRENT;
  }

  private boolean hasSupportingReceivingAssessments(InternalId operationId) {
    return transfers
            .findDestinationChangeAssessment(
                operationId, ReceivingAssessment.Kind.CLINICAL_SUITABILITY)
            .filter(a -> a.outcome() == ReceivingAssessment.Outcome.SUPPORTS)
            .isPresent()
        && transfers
            .findDestinationChangeAssessment(
                operationId, ReceivingAssessment.Kind.OPERATIONAL_READINESS)
            .filter(a -> a.outcome() == ReceivingAssessment.Outcome.SUPPORTS)
            .isPresent();
  }

  private Resolution applyOutcome(
      InternalEvent event,
      JsonNode payload,
      InternalId transferId,
      InternalId transportRequestId,
      InternalId transportOperationId,
      ProviderOutcome outcome,
      Revalidation revalidation,
      ExecutionContext context,
      Instant occurredAt,
      Instant recordedAt) {
    if (!transfers.claimConsumerEvent(event.eventId(), event.eventType(), recordedAt)) {
      return Resolution.none();
    }
    InternalId operationId = id(payload, "transferDestinationChangeReference");
    TransferDestinationChangeOperation current =
        transfers.lockDestinationChange(operationId).orElseThrow(TransferNotFound::new);
    Optional<String> currentMismatch =
        linkageMismatch(current, payload, transferId, transportRequestId, transportOperationId);
    if (currentMismatch.isPresent()) {
      String reconciliationReason =
          "UNLINKED_DESTINATION_CHANGE_EVENT_" + currentMismatch.orElseThrow();
      auditReconciliation(context, current.id(), reconciliationReason, recordedAt);
      transfers.completeConsumerEvent(
          event.eventId(), Optional.of(transferId), "UNLINKED_DESTINATION_CHANGE", recordedAt);
      return Resolution.none();
    }
    if (current.providerOutcome().isPresent()) {
      if (current.providerOutcome().orElseThrow() == outcome) {
        boolean pending =
            current.status() == Status.OLD_HOLD_RELEASE_PENDING
                || current.status() == Status.PROPOSED_HOLD_RELEASE_PENDING;
        auditReconciliation(
            context, current.id(), "DUPLICATE_DESTINATION_CHANGE_OUTCOME", recordedAt);
        transfers.completeConsumerEvent(
            event.eventId(),
            Optional.of(transferId),
            "DUPLICATE_DESTINATION_CHANGE_OUTCOME",
            recordedAt);
        return pending ? new Resolution(current, true) : Resolution.none();
      }
      auditReconciliation(
          context, current.id(), "CONFLICTING_DESTINATION_CHANGE_OUTCOME", recordedAt);
      transfers.completeConsumerEvent(
          event.eventId(),
          Optional.of(transferId),
          "CONFLICTING_DESTINATION_CHANGE_OUTCOME",
          recordedAt);
      return Resolution.none();
    }

    TransferProgressTarget target =
        transfers.lockProgressTarget(transferId).orElseThrow(TransferNotFound::new);
    TransferStore.ActiveDestination active =
        transfers.findActiveDestination(transferId).orElseThrow(TransferNotFound::new);
    boolean linkedState =
        transfers.isSelectedTransportRequest(transferId, transportRequestId)
            && current.transportRequestId().equals(transportRequestId)
            && (current.status() == Status.TRANSPORT_ACK_PENDING
                || current.status() == Status.TRANSPORT_OUTCOME_UNKNOWN)
            && active.facility().equals(current.oldFacility())
            && active.reservationOperationId().equals(current.oldReservationOperationId())
            && target.receivingFacility().equals(current.oldFacility())
            && occurredAt.isAfter(current.createdAt());
    boolean accepted =
        outcome == ProviderOutcome.ACCEPTED
            && linkedState
            && target.transfer().status() == CoordinatedTransfer.Status.IN_TRANSIT
            && revalidation == Revalidation.CURRENT;
    Status nextStatus;
    String auditAction;
    String resolutionCode;
    if (outcome == ProviderOutcome.ACCEPTED && accepted) {
      if (!transfers.updateActiveDestination(
          transferId,
          current.oldFacility(),
          current.proposedOrganization(),
          current.proposedFacility(),
          current.capacityReservationOperationId(),
          current.id(),
          recordedAt)) {
        accepted = false;
      }
    }
    if (outcome == ProviderOutcome.REJECTED && linkedState) {
      nextStatus = Status.PROPOSED_HOLD_RELEASE_PENDING;
      auditAction = "TRANSFER_DESTINATION_CHANGE_REJECTED_BY_TRANSPORT";
      resolutionCode = "TRANSPORT_DESTINATION_CHANGE_REJECTED";
    } else if (accepted) {
      nextStatus = Status.OLD_HOLD_RELEASE_PENDING;
      auditAction = "TRANSFER_DESTINATION_CHANGE_APPLIED";
      resolutionCode = "TRANSPORT_DESTINATION_CHANGE_ACCEPTED";
    } else {
      nextStatus = Status.RECONCILIATION_REQUIRED;
      auditAction = "TRANSFER_DESTINATION_CHANGE_REQUIRES_RECONCILIATION";
      resolutionCode =
          outcome == ProviderOutcome.ACCEPTED && revalidation == Revalidation.STALE
              ? "ACCEPTED_DESTINATION_CHANGE_NO_LONGER_APPLICABLE"
              : "DESTINATION_CHANGE_LINKAGE_OR_STATE_CONFLICT";
    }
    TransferDestinationChangeOperation updated =
        copy(current, nextStatus, Optional.of(outcome), recordedAt);
    if (!transfers.updateDestinationChange(updated, current.version())) {
      throw new IllegalStateException("Transfer destination-change event raced another update");
    }
    audit.recordMutation(
        new AuditEvidence(
            AuditActionKind.PROTECTED_MUTATION,
            Optional.of(context.actor()),
            Optional.of(context.scope()),
            auditAction,
            "TransferDestinationChange",
            Optional.of(current.id()),
            recordedAt,
            nextStatus == Status.RECONCILIATION_REQUIRED
                ? AuditOutcome.FAILED
                : AuditOutcome.SUCCEEDED,
            Optional.of(resolutionCode),
            AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
            AuditAuthorizationOutcome.ALLOWED,
            context.correlationId(),
            Optional.of(target(context))));
    transfers.completeConsumerEvent(
        event.eventId(), Optional.of(transferId), resolutionCode, recordedAt);
    boolean releasePending =
        nextStatus == Status.OLD_HOLD_RELEASE_PENDING
            || nextStatus == Status.PROPOSED_HOLD_RELEASE_PENDING;
    return releasePending ? new Resolution(updated, true) : Resolution.none();
  }

  private void attemptCapacityRelease(TransferDestinationChangeOperation operation) {
    Status pendingStatus = operation.status();
    boolean accepted = pendingStatus == Status.OLD_HOLD_RELEASE_PENDING;
    if (!accepted && pendingStatus != Status.PROPOSED_HOLD_RELEASE_PENDING) return;
    ExternalReference facility = accepted ? operation.oldFacility() : operation.proposedFacility();
    ExternalReference organization =
        accepted ? operation.oldOrganization() : operation.proposedOrganization();
    InternalId reservationOperation =
        accepted
            ? operation.oldReservationOperationId()
            : operation.capacityReservationOperationId();
    InternalId releaseOperation =
        accepted
            ? operation.oldHoldReleaseOperationId()
            : operation.proposedHoldReleaseOperationId();
    ExecutionContext context =
        contexts.forBackgroundWork(
            ActorReference.service(
                new IssuerId(authentication.issuer()), properties.getReservationWorkerClientId()),
            RequestedExecutionScope.facility(organization, facility),
            operation.correlationId(),
            Optional.of(new CausationId(operation.id().toString())));
    if (!authorization
        .evaluate(context, TransferAuthorities.reservationReconciliation().code())
        .isAllowed()) {
      logger.warn(
          "Transfer destination-change Capacity release deferred correlationId={} reason=authorization",
          operation.correlationId());
      return;
    }
    try {
      CapacityReleaseResult released =
          capacity.release(context, releaseOperation, reservationOperation, operation.transferId());
      if (!isConfirmedRelease(context, operation, released, reservationOperation)) return;
      transaction.executeWithoutResult(
          ignored -> {
            TransferDestinationChangeOperation current =
                transfers.lockDestinationChange(operation.id()).orElseThrow(TransferNotFound::new);
            if (current.status() == Status.RESOLVED) return;
            if (current.status() != pendingStatus
                || !current.providerOutcome().equals(operation.providerOutcome())) return;
            TransferDestinationChangeOperation resolved =
                copy(current, Status.RESOLVED, current.providerOutcome(), clock.instant());
            if (!transfers.updateDestinationChange(resolved, current.version())) {
              throw new IllegalStateException(
                  "Transfer destination-change release raced another update");
            }
            audit.recordMutation(
                new AuditEvidence(
                    AuditActionKind.PROTECTED_MUTATION,
                    Optional.of(context.actor()),
                    Optional.of(context.scope()),
                    accepted
                        ? "TRANSFER_DESTINATION_CHANGE_OLD_CAPACITY_RELEASE_CONFIRMED"
                        : "TRANSFER_DESTINATION_CHANGE_PROPOSED_CAPACITY_RELEASE_CONFIRMED",
                    "TransferDestinationChange",
                    Optional.of(current.id()),
                    clock.instant(),
                    AuditOutcome.SUCCEEDED,
                    Optional.of(released.outcome().name()),
                    AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                    AuditAuthorizationOutcome.ALLOWED,
                    context.correlationId(),
                    Optional.of(target(context))));
          });
    } catch (RuntimeException failure) {
      logger.warn(
          "Transfer destination-change Capacity cleanup remains pending correlationId={} failureType={}",
          operation.correlationId(),
          failure.getClass().getSimpleName());
    }
  }

  private boolean isConfirmedRelease(
      ExecutionContext context,
      TransferDestinationChangeOperation operation,
      CapacityReleaseResult release,
      InternalId reservationOperation) {
    if (release.outcome() == CapacityReleaseResult.Outcome.NOT_RESERVED
        || release.reservationId().isEmpty()) {
      CapacityReservationOutcome current =
          capacity.getReservationOutcome(context, reservationOperation, operation.transferId());
      return current.operationId().equals(reservationOperation)
          && current.transferId().equals(operation.transferId())
          && (current.status() == CapacityReservationOutcome.Status.RELEASED
              || current.status() == CapacityReservationOutcome.Status.EXPIRED);
    }
    CapacityReservationOutcome current =
        capacity.getReservationOutcome(context, reservationOperation, operation.transferId());
    return current.operationId().equals(reservationOperation)
        && current.transferId().equals(operation.transferId())
        && current.reservationId().equals(release.reservationId())
        && (current.status() == CapacityReservationOutcome.Status.RELEASED
            || current.status() == CapacityReservationOutcome.Status.EXPIRED);
  }

  private void reconcileUnlinkedEvent(
      InternalEvent event,
      InternalId transferId,
      ExecutionContext context,
      Instant recordedAt,
      String reason) {
    transaction.executeWithoutResult(
        ignored -> {
          if (!transfers.claimConsumerEvent(event.eventId(), event.eventType(), recordedAt)) return;
          String auditReason = "UNLINKED_DESTINATION_CHANGE_EVENT_" + reason;
          auditReconciliation(context, transferId, auditReason, recordedAt);
          transfers.completeConsumerEvent(
              event.eventId(), Optional.of(transferId), "UNLINKED_DESTINATION_CHANGE", recordedAt);
        });
  }

  private Optional<String> linkageMismatch(
      TransferDestinationChangeOperation operation,
      JsonNode payload,
      InternalId transferId,
      InternalId transportRequestId,
      InternalId transportOperationId) {
    if (!operation.transferId().equals(transferId))
      return Optional.of("TRANSFER_REFERENCE_MISMATCH");
    if (!operation.transportRequestId().equals(transportRequestId)) {
      return Optional.of("REQUEST_REFERENCE_MISMATCH");
    }
    if (!operation.transportOperationId().equals(transportOperationId)) {
      return Optional.of("TRANSPORT_OPERATION_REFERENCE_MISMATCH");
    }
    if (!operation.oldFacility().value().equals(text(payload, "oldDestinationFacilityReference"))) {
      return Optional.of("OLD_DESTINATION_MISMATCH");
    }
    if (!operation
        .proposedFacility()
        .value()
        .equals(text(payload, "proposedDestinationFacilityReference"))) {
      return Optional.of("PROPOSED_DESTINATION_MISMATCH");
    }
    return Optional.empty();
  }

  private boolean hasOriginScope(JsonNode envelope, TransferProgressTarget target) {
    JsonNode scope = envelope.get("executionScope");
    if (scope == null || !scope.isObject()) return false;
    JsonNode organization = scope.get("organizationRef");
    JsonNode facility = scope.get("facilityRef");
    return referenceMatches(organization, "Organization", target.originOrganization())
        && target
            .originFacility()
            .filter(expected -> referenceMatches(facility, "Facility", expected))
            .isPresent();
  }

  private static boolean referenceMatches(JsonNode value, String type, ExternalReference expected) {
    return value != null
        && value.isObject()
        && DIRECTORY_ISSUER.equals(text(value, "issuer"))
        && type.equals(text(value, "resourceType"))
        && expected.value().equals(text(value, "value"));
  }

  private ExecutionContext originContext(
      TransferProgressTarget target, JsonNode envelope, InternalId eventId) {
    ExternalReference facility = target.originFacility().orElseThrow(TransferNotFound::new);
    return contexts.forBackgroundWork(
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getOwnerEventConsumerClientId()),
        RequestedExecutionScope.facility(target.originOrganization(), facility),
        new CorrelationId(text(envelope, "correlationId")),
        Optional.of(new CausationId(eventId.toString())));
  }

  private ExecutionContext capacityContext(
      TransferDestinationChangeOperation operation, InternalEvent event) {
    return contexts.forBackgroundWork(
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getReservationWorkerClientId()),
        RequestedExecutionScope.facility(
            operation.proposedOrganization(), operation.proposedFacility()),
        operation.correlationId(),
        Optional.of(new CausationId(event.eventId().toString())));
  }

  private void auditDenied(ExecutionContext context, InternalId transferId) {
    audit.recordNonMutating(
        new AuditEvidence(
            AuditActionKind.SECURITY_OUTCOME,
            Optional.of(context.actor()),
            Optional.of(context.scope()),
            "TRANSFER_DESTINATION_CHANGE_EVENT_DENIED",
            "TransferEventReconciliation",
            Optional.of(transferId),
            clock.instant(),
            AuditOutcome.DENIED,
            Optional.of("AUTHORIZATION_DENIED"),
            AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
            AuditAuthorizationOutcome.DENIED,
            context.correlationId(),
            Optional.of(target(context))));
  }

  private void auditReconciliation(
      ExecutionContext context, InternalId operationId, String outcome, Instant recordedAt) {
    audit.recordMutation(
        new AuditEvidence(
            AuditActionKind.PROTECTED_MUTATION,
            Optional.of(context.actor()),
            Optional.of(context.scope()),
            "TRANSFER_DESTINATION_CHANGE_EVENT_RECONCILIATION",
            "TransferDestinationChange",
            Optional.of(operationId),
            recordedAt,
            AuditOutcome.FAILED,
            Optional.of(outcome),
            AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
            AuditAuthorizationOutcome.ALLOWED,
            context.correlationId(),
            Optional.of(target(context))));
  }

  private static AuditTargetContext target(ExecutionContext context) {
    return new AuditTargetContext(
        context.scope().kind(), context.scope().organization(), context.scope().facility());
  }

  private static TransferDestinationChangeOperation copy(
      TransferDestinationChangeOperation current,
      Status status,
      Optional<ProviderOutcome> outcome,
      Instant updatedAt) {
    return new TransferDestinationChangeOperation(
        current.id(),
        current.transferId(),
        current.transportRequestId(),
        current.oldOrganization(),
        current.oldFacility(),
        current.proposedOrganization(),
        current.proposedFacility(),
        current.recommendationId(),
        current.referralRevision(),
        current.reasonCode(),
        current.reservationPlan(),
        status,
        current.capacityReservationOperationId(),
        current.capacityReservationOutcome(),
        current.oldReservationOperationId(),
        current.oldHoldReleaseOperationId(),
        current.proposedHoldReleaseOperationId(),
        current.transportOperationId(),
        outcome,
        current.initiatedBy(),
        current.originOrganization(),
        current.originFacility(),
        current.correlationId(),
        current.version() + 1,
        current.createdAt(),
        updatedAt);
  }

  private static JsonNode object(JsonNode document, String field) {
    JsonNode value = document.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(
          "Transport destination-change event is missing an object field");
    }
    return value;
  }

  private static InternalId id(JsonNode document, String field) {
    return InternalId.parse(text(document, field));
  }

  private static String text(JsonNode document, String field) {
    JsonNode value = document.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(
          "Transport destination-change event is missing a text field");
    }
    return value.textValue();
  }

  private enum Revalidation {
    CURRENT,
    STALE
  }

  private record Resolution(TransferDestinationChangeOperation operation, boolean releasePending) {
    private static Resolution none() {
      return new Resolution(null, false);
    }
  }

  private static final class TransferNotFound extends RuntimeException {
    private TransferNotFound() {
      super("Transfer destination-change event has no linked Transfer state");
    }
  }
}
