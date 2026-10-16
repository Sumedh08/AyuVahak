package org.ayuvahak.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
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
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.adapter.configuration.TransferProperties;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferProgressFact;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Transfer-owned validation and application of minimal Transport movement events. */
@Component
@Lazy
public final class TransferTransportMovementEventHandler implements InternalEventHandler {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final EventType DEPARTURE = EventType.parse("transport.departure-confirmed.v1");
  private static final EventType ARRIVAL = EventType.parse("transport.arrival-reported.v1");
  private static final EventType INTERRUPTION = EventType.parse("transport.interrupted.v1");
  private static final EventType UNFULFILLED = EventType.parse("transport.unfulfilled.v1");
  private static final EventType CANCELLATION_RESOLVED =
      EventType.parse("transport.cancellation-resolved.v1");

  private final TransferStore transfers;
  private final ObjectProvider<TransferProgressCommands> progress;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<TransferCapacityReservationRecovery> reservationRecovery;
  private final AuditWriter audit;
  private final ExecutionContextFactory contexts;
  private final TransactionTemplate transaction;
  private final ActorReference worker;
  private final Clock clock;
  private final ObjectMapper mapper;

  public TransferTransportMovementEventHandler(
      @Lazy TransferStore transfers,
      ObjectProvider<TransferProgressCommands> progress,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<TransferCapacityReservationRecovery> reservationRecovery,
      AuditWriter audit,
      ExecutionContextFactory contexts,
      @Lazy PlatformTransactionManager transactionManager,
      TransferProperties properties,
      AuthenticationProperties authentication,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock,
      ObjectMapper mapper) {
    this.transfers = transfers;
    this.progress = progress;
    this.authorization = authorization;
    this.reservationRecovery = reservationRecovery;
    this.audit = audit;
    this.contexts = contexts;
    this.transaction = new TransactionTemplate(transactionManager);
    this.worker =
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getOwnerEventConsumerClientId());
    this.clock = clock;
    this.mapper = mapper;
  }

  @Override
  public Set<EventType> supportedEventTypes() {
    return Set.of(DEPARTURE, ARRIVAL, INTERRUPTION, UNFULFILLED, CANCELLATION_RESOLVED);
  }

  @Override
  public void handle(InternalEvent event) throws Exception {
    if (transfers.isConsumerEventCompleted(event.eventId(), event.eventType())) {
      return;
    }
    JsonNode envelope = mapper.readTree(event.envelopeJson());
    JsonNode payload = object(envelope, "payload");
    InternalId transferId = InternalId.parse(text(payload, "transferReference"));
    InternalId transportRequestId = InternalId.parse(text(payload, "transportRequestReference"));
    TransferProgressTarget target =
        transfers.findProgressTarget(transferId).orElseThrow(TransferNotFound::new);
    ExecutionContext context = contextForOrigin(target, envelope, event.eventId());
    requireSourceScope(envelope, payload, target);
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, TransferAuthorities.progress().code());
    if (!decision.isAllowed()) {
      auditDenied(context, transferId, decision);
      throw new IllegalStateException("Transfer movement event consumer is not authorized");
    }
    if (event.eventType().equals(CANCELLATION_RESOLVED)) {
      boolean[] releaseCapacity = {false};
      transaction.executeWithoutResult(
          status ->
              releaseCapacity[0] =
                  processCancellation(
                      event, envelope, payload, transferId, transportRequestId, context));
      if (releaseCapacity[0]) {
        reservationRecovery
            .getObject()
            .reconcileCancellation(
                transferId,
                context.correlationId(),
                Optional.of(new CausationId(event.eventId().toString())));
      }
      return;
    }
    transaction.executeWithoutResult(
        status -> process(event, envelope, payload, transferId, transportRequestId, context));
  }

  private boolean processCancellation(
      InternalEvent event,
      JsonNode envelope,
      JsonNode payload,
      InternalId transferId,
      InternalId transportRequestId,
      ExecutionContext context) {
    Instant recordedAt = clock.instant();
    if (!transfers.claimConsumerEvent(event.eventId(), event.eventType(), recordedAt)) {
      return false;
    }
    TransferProgressTarget target =
        transfers.lockProgressTarget(transferId).orElseThrow(TransferNotFound::new);
    if (!transfers.isSelectedTransportRequest(transferId, transportRequestId)) {
      completeWithoutTransition(event, transferId, context, "STALE_TRANSPORT_REQUEST", recordedAt);
      return false;
    }
    if (!text(payload, "originFacilityReference")
            .equals(target.originFacility().map(ExternalReference::value).orElse(""))
        || !text(payload, "destinationFacilityReference")
            .equals(target.receivingFacility().value())) {
      completeWithoutTransition(
          event, transferId, context, "CANCELLATION_SCOPE_MISMATCH", recordedAt);
      return false;
    }
    String outcome = text(payload, "outcome");
    if (!Set.of("CANCELLED_BEFORE_MOVEMENT", "CANCEL_REJECTED_ACTIVE", "MOVEMENT_ALREADY_STARTED")
        .contains(outcome)) {
      throw new IllegalArgumentException("Transport cancellation outcome is unsupported");
    }
    InternalId operationId = InternalId.parse(text(payload, "cancellationOperationReference"));
    Instant occurredAt = Instant.parse(text(envelope, "occurredAt"));
    if (!transfers.recordTransportCancellationOutcome(
        transferId,
        transportRequestId,
        operationId,
        outcome,
        event.eventId(),
        occurredAt,
        recordedAt,
        text(envelope, "correlationId"))) {
      completeWithoutTransition(
          event, transferId, context, "CANCELLATION_OPERATION_ALREADY_RECONCILED", recordedAt);
      return false;
    }
    completeWithoutTransition(
        event, transferId, context, "TRANSPORT_CANCELLATION_" + outcome, recordedAt);
    return outcome.equals("CANCELLED_BEFORE_MOVEMENT")
        && target.transfer().status() == CoordinatedTransfer.Status.CANCELLATION_PENDING_RELEASE;
  }

  private void process(
      InternalEvent event,
      JsonNode envelope,
      JsonNode payload,
      InternalId transferId,
      InternalId transportRequestId,
      ExecutionContext context) {
    Instant recordedAt = clock.instant();
    if (!transfers.claimConsumerEvent(event.eventId(), event.eventType(), recordedAt)) {
      return;
    }
    TransferProgressTarget target =
        transfers.lockProgressTarget(transferId).orElseThrow(TransferNotFound::new);
    if (!transfers.isSelectedTransportRequest(transferId, transportRequestId)) {
      completeWithoutTransition(event, transferId, context, "STALE_TRANSPORT_REQUEST", recordedAt);
      return;
    }
    if (!text(payload, "originFacilityReference")
        .equals(target.originFacility().map(ExternalReference::value).orElse(""))) {
      completeWithoutTransition(event, transferId, context, "ORIGIN_SCOPE_MISMATCH", recordedAt);
      return;
    }
    String evidenceKind = text(payload, "evidenceKind");
    InternalId evidenceId = InternalId.parse(text(payload, "evidenceReference"));
    Instant occurredAt = Instant.parse(text(envelope, "occurredAt"));
    Optional<String> reason = optionalText(payload, "reasonCode");
    String sourceReference = "transport-evidence/" + evidenceId;

    if (event.eventType().equals(DEPARTURE)) {
      if (!"EN_ROUTE_TO_DESTINATION".equals(evidenceKind)) {
        completeWithoutTransition(
            event, transferId, context, "INVALID_DEPARTURE_EVIDENCE", recordedAt);
      } else if (target.transfer().status() == CoordinatedTransfer.Status.READY
          || target.transfer().status()
              == CoordinatedTransfer.Status.CANCELLATION_PENDING_RELEASE) {
        recordProgress(
            context,
            transferId,
            TransferProgressFact.Kind.DEPARTURE,
            occurredAt,
            Optional.empty(),
            sourceReference,
            target.transfer().version(),
            event,
            payload);
        transfers.completeConsumerEvent(
            event.eventId(), Optional.of(transferId), "TRANSFER_DEPARTURE_RECORDED", recordedAt);
      } else if (target.transfer().status() == CoordinatedTransfer.Status.IN_TRANSIT) {
        completeWithoutTransition(
            event, transferId, context, "DEPARTURE_ALREADY_RECORDED", recordedAt);
      } else {
        completeWithoutTransition(
            event, transferId, context, "DEPARTURE_REQUIRES_RECONCILIATION", recordedAt);
      }
      return;
    }

    if (event.eventType().equals(ARRIVAL)) {
      if (!"ARRIVED_DESTINATION".equals(evidenceKind)
          || !text(payload, "destinationFacilityReference")
              .equals(target.receivingFacility().value())) {
        completeWithoutTransition(
            event, transferId, context, "ARRIVAL_DESTINATION_MISMATCH", recordedAt);
      } else if (target.transfer().status() == CoordinatedTransfer.Status.ARRIVED
          || target.transfer().status() == CoordinatedTransfer.Status.HANDED_OVER
          || target.transfer().status() == CoordinatedTransfer.Status.COMPLETED) {
        completeWithoutTransition(
            event, transferId, context, "ARRIVAL_ALREADY_RECORDED", recordedAt);
      } else {
        recordProgress(
            context,
            transferId,
            TransferProgressFact.Kind.ARRIVAL,
            occurredAt,
            Optional.empty(),
            sourceReference,
            target.transfer().version(),
            event,
            payload);
        transfers.completeConsumerEvent(
            event.eventId(), Optional.of(transferId), "TRANSFER_ARRIVAL_RECORDED", recordedAt);
      }
      return;
    }

    if (event.eventType().equals(INTERRUPTION)
        && "INTERRUPTED".equals(evidenceKind)
        && target.transfer().status() == CoordinatedTransfer.Status.IN_TRANSIT) {
      recordProgress(
          context,
          transferId,
          TransferProgressFact.Kind.INTERRUPTION,
          occurredAt,
          Optional.of(reason.orElse("TRANSPORT_INTERRUPTED")),
          sourceReference,
          target.transfer().version(),
          event,
          payload);
      transfers.completeConsumerEvent(
          event.eventId(), Optional.of(transferId), "TRANSFER_INTERRUPTION_RECORDED", recordedAt);
      return;
    }

    String outcome;
    if (event.eventType().equals(UNFULFILLED)) {
      if (!"UNFULFILLED".equals(evidenceKind)) {
        completeWithoutTransition(
            event, transferId, context, "INVALID_UNFULFILLED_EVIDENCE", recordedAt);
        return;
      }
      outcome = "TRANSPORT_UNFULFILLED_REQUIRES_RECONCILIATION";
    } else {
      outcome = "TRANSPORT_INTERRUPTION_REQUIRES_RECONCILIATION";
    }
    completeWithoutTransition(event, transferId, context, outcome, recordedAt);
  }

  private void recordProgress(
      ExecutionContext context,
      InternalId transferId,
      TransferProgressFact.Kind kind,
      Instant occurredAt,
      Optional<String> reason,
      String sourceReference,
      long expectedVersion,
      InternalEvent event,
      JsonNode payload) {
    progress
        .getObject()
        .record(
            context,
            transferId,
            kind,
            occurredAt,
            reason,
            Optional.of(sourceReference),
            expectedVersion,
            "transport-event-" + event.eventId(),
            RequestFingerprint.from(payload));
  }

  private void completeWithoutTransition(
      InternalEvent event,
      InternalId transferId,
      ExecutionContext context,
      String outcome,
      Instant recordedAt) {
    audit.recordMutation(
        new AuditEvidence(
            AuditActionKind.PROTECTED_MUTATION,
            Optional.of(context.actor()),
            Optional.of(context.scope()),
            "TRANSFER_TRANSPORT_EVENT_RECONCILIATION",
            "TransferEventReconciliation",
            Optional.of(transferId),
            recordedAt,
            AuditOutcome.SUCCEEDED,
            Optional.of(outcome),
            AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
            AuditAuthorizationOutcome.ALLOWED,
            context.correlationId(),
            Optional.of(
                new AuditTargetContext(
                    context.scope().kind(),
                    context.scope().organization(),
                    context.scope().facility()))));
    transfers.completeConsumerEvent(event.eventId(), Optional.of(transferId), outcome, recordedAt);
  }

  private ExecutionContext contextForOrigin(
      TransferProgressTarget target, JsonNode envelope, InternalId eventId) {
    ExternalReference facility = target.originFacility().orElseThrow(TransferNotFound::new);
    return contexts.forBackgroundWork(
        worker,
        RequestedExecutionScope.facility(target.originOrganization(), facility),
        new CorrelationId(text(envelope, "correlationId")),
        Optional.of(new CausationId(eventId.toString())));
  }

  private static void requireSourceScope(
      JsonNode envelope, JsonNode payload, TransferProgressTarget target) {
    JsonNode scope = object(envelope, "executionScope");
    JsonNode organization = scope.get("organizationRef");
    JsonNode facility = scope.get("facilityRef");
    String expectedFacility = target.originFacility().map(ExternalReference::value).orElse("");
    String expectedDestination = target.receivingFacility().value();
    if (organization == null
        || facility == null
        || !text(organization, "issuer").equals(DIRECTORY_ISSUER)
        || !text(organization, "resourceType").equals("Organization")
        || !text(organization, "value").equals(target.originOrganization().value())
        || !text(facility, "issuer").equals(DIRECTORY_ISSUER)
        || !text(facility, "resourceType").equals("Facility")
        || !text(facility, "value").equals(expectedFacility)
        || !text(payload, "originFacilityReference").equals(expectedFacility)
        || !text(payload, "destinationFacilityReference").equals(expectedDestination)) {
      throw new IllegalArgumentException(
          "Transport movement event scope does not match Transfer origin");
    }
  }

  private static JsonNode object(JsonNode document, String field) {
    JsonNode value = document.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Transfer movement event is missing an object field");
    }
    return value;
  }

  private static String text(JsonNode document, String field) {
    JsonNode value = document.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException("Transfer movement event is missing a text field");
    }
    return value.textValue();
  }

  private static Optional<String> optionalText(JsonNode document, String field) {
    JsonNode value = document.get(field);
    return value == null || value.isNull() ? Optional.empty() : Optional.of(text(document, field));
  }

  private void auditDenied(
      ExecutionContext context, InternalId transferId, AuthorizationDecision decision) {
    audit.recordNonMutating(
        new AuditEvidence(
            AuditActionKind.SECURITY_OUTCOME,
            Optional.of(context.actor()),
            Optional.of(context.scope()),
            "TRANSFER_TRANSPORT_MOVEMENT_EVENT_DENIED",
            "TransferEventReconciliation",
            Optional.of(transferId),
            clock.instant(),
            AuditOutcome.DENIED,
            Optional.of(
                decision.denialReason().orElse(null)
                        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
                    ? "AUTHORIZATION_STORE_UNAVAILABLE"
                    : "AUTHORIZATION_DENIED"),
            AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
            AuditAuthorizationOutcome.DENIED,
            context.correlationId(),
            Optional.of(
                new AuditTargetContext(
                    context.scope().kind(),
                    context.scope().organization(),
                    context.scope().facility()))));
  }

  private static final class TransferNotFound extends RuntimeException {
    private TransferNotFound() {
      super("Transfer movement event has no current Transfer target");
    }
  }
}
