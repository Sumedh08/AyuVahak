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
import org.ayuvahak.transfer.domain.CapacityReservationIntent;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferProgressFact;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Transfer-owned, deduplicated reactions to Referral and Capacity owner events. */
@Component
@Lazy
public final class TransferOwnerEventHandler implements InternalEventHandler {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final EventType REFERRAL_CANCELLED = EventType.parse("referral.cancelled.v1");
  private static final EventType RESERVATION_EXPIRED =
      EventType.parse("capacity.reservation-expired.v1");
  private static final EventType RESERVATION_AT_RISK =
      EventType.parse("capacity.reservation-at-risk.v1");

  private final TransferStore transfers;
  private final ObjectProvider<TransferProgressCommands> progress;
  private final TransferCapacityReservationRecovery reservationRecovery;
  private final ExecutionContextFactory contexts;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final TransactionTemplate denialTransaction;
  private final TransferProperties properties;
  private final ActorReference worker;
  private final Clock clock;
  private final ObjectMapper mapper;

  public TransferOwnerEventHandler(
      @Lazy TransferStore transfers,
      ObjectProvider<TransferProgressCommands> progress,
      TransferCapacityReservationRecovery reservationRecovery,
      ExecutionContextFactory contexts,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      TransferProperties properties,
      AuthenticationProperties authentication,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock,
      ObjectMapper mapper) {
    this.transfers = transfers;
    this.progress = progress;
    this.reservationRecovery = reservationRecovery;
    this.contexts = contexts;
    this.authorization = authorization;
    this.auditWriter = auditWriter;
    this.transaction = new TransactionTemplate(transactionManager);
    this.denialTransaction = new TransactionTemplate(transactionManager);
    this.denialTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.properties = properties;
    this.worker =
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getOwnerEventConsumerClientId());
    this.clock = clock;
    this.mapper = mapper;
  }

  @Override
  public Set<EventType> supportedEventTypes() {
    return Set.of(REFERRAL_CANCELLED, RESERVATION_EXPIRED, RESERVATION_AT_RISK);
  }

  @Override
  public void handle(InternalEvent event) throws Exception {
    JsonNode envelope = mapper.readTree(event.envelopeJson());
    JsonNode payload = object(envelope, "payload");
    Instant occurredAt = Instant.parse(text(envelope, "occurredAt"));
    Optional<InternalId> cancellationToRelease;
    try {
      cancellationToRelease =
          transaction.execute(status -> process(event, envelope, payload, occurredAt));
    } catch (AuthorizationRejected denied) {
      denialTransaction.executeWithoutResult(status -> auditDenied(denied));
      throw denied;
    }
    cancellationToRelease.ifPresent(reservationRecovery::reconcileCancellation);
  }

  private Optional<InternalId> process(
      InternalEvent event, JsonNode envelope, JsonNode payload, Instant occurredAt) {
    Instant recordedAt = clock.instant();
    authorizeEvent(event, envelope, payload);
    if (!transfers.claimConsumerEvent(event.eventId(), event.eventType(), recordedAt)) {
      return Optional.empty();
    }
    if (event.eventType().equals(REFERRAL_CANCELLED)) {
      return processReferralCancellation(event, envelope, payload, occurredAt, recordedAt);
    }
    processCapacityEvent(event, envelope, payload, occurredAt, recordedAt);
    return Optional.empty();
  }

  private void authorizeEvent(InternalEvent event, JsonNode envelope, JsonNode payload) {
    if (event.eventType().equals(REFERRAL_CANCELLED)) {
      InternalId referralId = InternalId.parse(text(payload, "referralReference"));
      Optional<TransferJourney> journey = transfers.findJourneyByReferral(referralId);
      ExecutionContext originContext =
          journey
              .map(value -> contextForJourneyOrigin(value, envelope, event.eventId()))
              .orElseGet(() -> contextFromEnvelope(envelope, event.eventId()));
      requireAuthority(
          originContext, TransferAuthorities.referralCancellation().code(), referralId);
      if (journey.isEmpty()) {
        return;
      }
      TransferJourney found = journey.orElseThrow();
      TransferRequestAttempt latest = transfers.findLatestRequest(found.id()).orElseThrow();
      Optional<InternalId> transferId =
          transfers
              .findDecisionByVersion(latest.id(), latest.version())
              .flatMap(result -> result.transfer().map(CoordinatedTransfer::id));
      if (transferId.isPresent()) {
        Optional<TransferProgressTarget> target =
            transfers.findProgressTarget(transferId.orElseThrow());
        if (target.isPresent()) {
          ExecutionContext transferContext =
              contextForTransfer(target.orElseThrow(), envelope, event.eventId());
          requireAuthority(
              transferContext,
              TransferAuthorities.reservationReconciliation().code(),
              transferId.orElseThrow());
        }
      }
      return;
    }

    InternalId transferId = InternalId.parse(text(payload, "transferReference"));
    ExecutionContext context =
        transfers
            .findProgressTarget(transferId)
            .map(target -> contextForTransfer(target, envelope, event.eventId()))
            .orElseGet(() -> contextFromEnvelope(envelope, event.eventId()));
    requireAuthority(context, TransferAuthorities.reservationReconciliation().code(), transferId);
  }

  private void requireAuthority(
      ExecutionContext context, String authorityCode, InternalId targetId) {
    AuthorizationDecision decision = authorization.getObject().evaluate(context, authorityCode);
    if (!decision.isAllowed()) {
      throw new AuthorizationRejected(context, targetId, authorityCode, decision);
    }
  }

  private Optional<InternalId> processReferralCancellation(
      InternalEvent event,
      JsonNode envelope,
      JsonNode payload,
      Instant occurredAt,
      Instant recordedAt) {
    InternalId referralId = InternalId.parse(text(payload, "referralReference"));
    Optional<TransferJourney> found = transfers.findJourneyByReferral(referralId);
    if (found.isEmpty()) {
      ExecutionContext context = contextFromEnvelope(envelope, event.eventId());
      auditNoState(context, referralId, "TRANSFER_REFERRAL_CANCEL_NO_JOURNEY");
      transfers.completeConsumerEvent(
          event.eventId(), Optional.empty(), "NO_TRANSFER_JOURNEY", recordedAt);
      return Optional.empty();
    }
    TransferJourney journey = found.orElseThrow();
    TransferRequestAttempt latest = transfers.findLatestRequest(journey.id()).orElseThrow();
    Optional<InternalId> transferId =
        transfers
            .findDecisionByVersion(latest.id(), latest.version())
            .flatMap(result -> result.transfer().map(CoordinatedTransfer::id));
    ExecutionContext context = contextForJourney(journey, transferId, event.eventId(), envelope);
    String outcome = "NO_STATE_CHANGE_TERMINAL";
    Optional<InternalId> pendingRelease = Optional.empty();
    if (latest.status() == TransferRequestAttempt.Status.PENDING) {
      boolean changed =
          occurredAt.isBefore(latest.expiresAt())
              && transfers.withdrawPendingRequest(
                  latest.id(), latest.version(), occurredAt, recordedAt);
      if (changed) {
        outcome = "PENDING_REQUEST_WITHDRAWN";
        auditNoState(context, latest.id(), "TRANSFER_REFERRAL_CANCEL_WITHDRAW");
      } else if (!occurredAt.isBefore(latest.expiresAt())
          && transfers.expirePendingRequest(latest.id(), latest.version(), recordedAt)) {
        outcome = "PENDING_REQUEST_EXPIRED";
        auditNoState(context, latest.id(), "TRANSFER_REFERRAL_CANCEL_EXPIRE");
      }
    } else if (transferId.isPresent()) {
      CoordinatedTransfer transfer = transfers.findTransfer(transferId.orElseThrow()).orElseThrow();
      if (isPreDeparture(transfer.status())) {
        var body = mapper.createObjectNode();
        body.put("referralReference", referralId.toString());
        body.put("eventReference", event.eventId().toString());
        progress
            .getObject()
            .record(
                context,
                transfer.id(),
                TransferProgressFact.Kind.REFERRAL_CANCELLATION,
                occurredAt,
                Optional.of("REFERRAL_CANCELLED"),
                Optional.of("event:" + event.eventId()),
                transfer.version(),
                "referral-event-" + event.eventId(),
                RequestFingerprint.from(body));
        outcome = "CANCELLATION_PENDING_RELEASE";
        pendingRelease = Optional.of(transfer.id());
      }
    }
    transfers.completeConsumerEvent(event.eventId(), transferId, outcome, recordedAt);
    return pendingRelease;
  }

  private void processCapacityEvent(
      InternalEvent event,
      JsonNode envelope,
      JsonNode payload,
      Instant occurredAt,
      Instant recordedAt) {
    InternalId transferId = InternalId.parse(text(payload, "transferReference"));
    InternalId reservationId = InternalId.parse(text(payload, "reservationReference"));
    long sequence = event.aggregatePosition().map(position -> position.sequence()).orElse(0L);
    Optional<CapacityReservationIntent> intent = transfers.findReservationIntent(transferId);
    boolean ownerReferenceMatches =
        event
            .aggregatePosition()
            .map(
                position -> position.aggregateRef().equals("capacity-reservation/" + reservationId))
            .orElse(false);
    if (!ownerReferenceMatches) {
      auditNoState(
          contextFromEnvelope(envelope, event.eventId()),
          transferId,
          "CAPACITY_EVENT_REFERENCE_MISMATCH");
      transfers.completeConsumerEvent(
          event.eventId(), Optional.of(transferId), "RESERVATION_REFERENCE_MISMATCH", recordedAt);
      return;
    }
    if (intent.isPresent()
        && intent.orElseThrow().reservationId().isEmpty()
        && unresolvedReservationOutcome(intent.orElseThrow().outcome())) {
      throw new IllegalStateException("Capacity event awaits reservation outcome reconciliation");
    }
    if (intent.isEmpty()
        || intent.orElseThrow().reservationId().filter(reservationId::equals).isEmpty()) {
      auditNoState(
          contextFromEnvelope(envelope, event.eventId()),
          transferId,
          "CAPACITY_EVENT_REFERENCE_MISMATCH");
      transfers.completeConsumerEvent(
          event.eventId(), Optional.of(transferId), "RESERVATION_REFERENCE_MISMATCH", recordedAt);
      return;
    }
    if (sequence < 1 || !transfers.advanceCapacityEventSequence(transferId, sequence)) {
      auditNoState(
          contextForTransfer(transferId, envelope, event.eventId()),
          transferId,
          "CAPACITY_EVENT_STALE");
      transfers.completeConsumerEvent(
          event.eventId(), Optional.of(transferId), "STALE_EVENT", recordedAt);
      return;
    }
    CoordinatedTransfer transfer = transfers.findTransfer(transferId).orElse(null);
    if (transfer == null) {
      transfers.completeConsumerEvent(
          event.eventId(), Optional.of(transferId), "NO_TRANSFER_STATE", recordedAt);
      return;
    }
    ExecutionContext context = contextForTransfer(transferId, envelope, event.eventId());
    if (event.eventType().equals(RESERVATION_EXPIRED)
        && transfer.status() == CoordinatedTransfer.Status.CANCELLATION_PENDING_RELEASE) {
      transfers.recordReservationLifecycle(
          transferId,
          sequence,
          CapacityReservationIntent.Outcome.EXPIRED,
          Optional.of("CAPACITY_RESERVATION_EXPIRED"));
      recordCapacityFact(
          event,
          transfer,
          context,
          occurredAt,
          TransferProgressFact.Kind.CANCELLATION_RELEASE,
          "CAPACITY_EXPIRED");
      transfers.completeConsumerEvent(
          event.eventId(), Optional.of(transferId), "CANCELLATION_CONFIRMED_EXPIRED", recordedAt);
      return;
    }
    if (isPreDeparture(transfer.status())) {
      String reason =
          event.eventType().equals(RESERVATION_EXPIRED)
              ? "CAPACITY_RESERVATION_EXPIRED"
              : "CAPACITY_RESERVATION_AT_RISK";
      transfers.recordReservationLifecycle(
          transferId,
          sequence,
          event.eventType().equals(RESERVATION_EXPIRED)
              ? CapacityReservationIntent.Outcome.EXPIRED
              : CapacityReservationIntent.Outcome.AT_RISK,
          Optional.of(reason));
      recordCapacityFact(
          event,
          transfer,
          context,
          occurredAt,
          TransferProgressFact.Kind.CAPACITY_EXCEPTION,
          reason);
      transfers.completeConsumerEvent(
          event.eventId(),
          Optional.of(transferId),
          event.eventType().equals(RESERVATION_EXPIRED)
              ? "EXPIRED_EXCEPTION_RECORDED"
              : "AT_RISK_EXCEPTION_RECORDED",
          recordedAt);
    } else {
      transfers.recordReservationLifecycle(
          transferId,
          sequence,
          event.eventType().equals(RESERVATION_EXPIRED)
              ? CapacityReservationIntent.Outcome.EXPIRED
              : CapacityReservationIntent.Outcome.AT_RISK,
          Optional.of(
              event.eventType().equals(RESERVATION_EXPIRED)
                  ? "CAPACITY_RESERVATION_EXPIRED"
                  : "CAPACITY_RESERVATION_AT_RISK"));
      auditNoState(context, transferId, "CAPACITY_EVENT_DURING_OR_AFTER_MOVEMENT");
      transfers.completeConsumerEvent(
          event.eventId(), Optional.of(transferId), "MOVEMENT_STATE_PRESERVED", recordedAt);
    }
  }

  private void recordCapacityFact(
      InternalEvent event,
      CoordinatedTransfer transfer,
      ExecutionContext context,
      Instant occurredAt,
      TransferProgressFact.Kind kind,
      String reason) {
    var body = mapper.createObjectNode();
    body.put("eventReference", event.eventId().toString());
    body.put("reservationSequence", event.aggregatePosition().orElseThrow().sequence());
    progress
        .getObject()
        .record(
            context,
            transfer.id(),
            kind,
            occurredAt,
            Optional.of(reason),
            Optional.of("event:" + event.eventId()),
            transfer.version(),
            "capacity-event-" + event.eventId(),
            RequestFingerprint.from(body));
  }

  private ExecutionContext contextForJourney(
      TransferJourney journey,
      Optional<InternalId> transferId,
      InternalId eventId,
      JsonNode envelope) {
    if (transferId.isPresent()) {
      return contextForTransfer(transferId.orElseThrow(), envelope, eventId);
    }
    return contextForJourneyOrigin(journey, envelope, eventId);
  }

  private ExecutionContext contextForJourneyOrigin(
      TransferJourney journey, JsonNode envelope, InternalId eventId) {
    RequestedExecutionScope scope =
        journey
            .originFacility()
            .<RequestedExecutionScope>map(
                facility ->
                    RequestedExecutionScope.facility(journey.originOrganization(), facility))
            .orElseGet(() -> RequestedExecutionScope.organization(journey.originOrganization()));
    return background(scope, envelope, eventId);
  }

  private ExecutionContext contextForTransfer(
      InternalId transferId, JsonNode envelope, InternalId eventId) {
    TransferProgressTarget target = transfers.findProgressTarget(transferId).orElseThrow();
    return contextForTransfer(target, envelope, eventId);
  }

  private ExecutionContext contextForTransfer(
      TransferProgressTarget target, JsonNode envelope, InternalId eventId) {
    return background(
        RequestedExecutionScope.facility(
            target.receivingOrganization(), target.receivingFacility()),
        envelope,
        eventId);
  }

  private ExecutionContext contextFromEnvelope(JsonNode envelope, InternalId eventId) {
    JsonNode scope = object(envelope, "executionScope");
    JsonNode organizationNode = scope.get("organizationRef");
    if (organizationNode == null) {
      throw new IllegalArgumentException("owner event must carry an organization scope");
    }
    ExternalReference organization = reference(organizationNode);
    JsonNode facilityNode = scope.get("facilityRef");
    RequestedExecutionScope requested =
        facilityNode == null
            ? RequestedExecutionScope.organization(organization)
            : RequestedExecutionScope.facility(organization, reference(facilityNode));
    return background(requested, envelope, eventId);
  }

  private ExecutionContext background(
      RequestedExecutionScope scope, JsonNode envelope, InternalId eventId) {
    return contexts.forBackgroundWork(
        worker,
        scope,
        new CorrelationId(text(envelope, "correlationId")),
        Optional.of(new CausationId(eventId.toString())));
  }

  private void auditNoState(ExecutionContext context, InternalId target, String outcome) {
    auditWriter
        .getObject()
        .recordMutation(
            new AuditEvidence(
                AuditActionKind.PROTECTED_MUTATION,
                Optional.of(context.actor()),
                Optional.of(context.scope()),
                outcome,
                "TransferEventReconciliation",
                Optional.of(target),
                clock.instant(),
                AuditOutcome.SUCCEEDED,
                Optional.of("NO_STATE_CHANGE"),
                AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId(),
                Optional.of(
                    new AuditTargetContext(
                        context.scope().kind(),
                        context.scope().organization(),
                        context.scope().facility()))));
  }

  private void auditDenied(AuthorizationRejected denied) {
    ExecutionContext context = denied.context();
    auditWriter
        .getObject()
        .recordNonMutating(
            new AuditEvidence(
                AuditActionKind.SECURITY_OUTCOME,
                Optional.of(worker),
                Optional.of(context.scope()),
                "TRANSFER_OWNER_EVENT_DENIED",
                "TransferEventReconciliation",
                Optional.of(denied.targetId()),
                clock.instant(),
                AuditOutcome.DENIED,
                Optional.of("AUTHORIZATION_DENIED"),
                AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                AuditAuthorizationOutcome.DENIED,
                context.correlationId(),
                Optional.of(
                    new AuditTargetContext(
                        context.scope().kind(),
                        context.scope().organization(),
                        context.scope().facility()))));
  }

  private static final class AuthorizationRejected extends RuntimeException {

    private final ExecutionContext context;
    private final InternalId targetId;
    private final String authorityCode;
    private final AuthorizationDecision decision;

    private AuthorizationRejected(
        ExecutionContext context,
        InternalId targetId,
        String authorityCode,
        AuthorizationDecision decision) {
      super("Transfer owner event consumer is not authorized for " + authorityCode);
      this.context = context;
      this.targetId = targetId;
      this.authorityCode = authorityCode;
      this.decision = decision;
    }

    private ExecutionContext context() {
      return context;
    }

    private InternalId targetId() {
      return targetId;
    }

    @SuppressWarnings("unused")
    private AuthorizationDecision decision() {
      return decision;
    }
  }

  private static boolean isPreDeparture(CoordinatedTransfer.Status status) {
    return status == CoordinatedTransfer.Status.AWAITING_RESERVATION
        || status == CoordinatedTransfer.Status.READY
        || status == CoordinatedTransfer.Status.COORDINATION_EXCEPTION;
  }

  private static boolean unresolvedReservationOutcome(CapacityReservationIntent.Outcome outcome) {
    return outcome == CapacityReservationIntent.Outcome.IN_PROGRESS
        || outcome == CapacityReservationIntent.Outcome.UNKNOWN
        || outcome == CapacityReservationIntent.Outcome.UNAVAILABLE;
  }

  private static ExternalReference reference(JsonNode reference) {
    return new ExternalReference(
        new IssuerId(text(reference, "issuer")),
        text(reference, "resourceType"),
        text(reference, "value"));
  }

  private static JsonNode object(JsonNode value, String field) {
    JsonNode child = value.get(field);
    if (child == null || !child.isObject()) {
      throw new IllegalArgumentException("owner event must contain an object field: " + field);
    }
    return child;
  }

  private static String text(JsonNode value, String field) {
    JsonNode child = value.get(field);
    if (child == null || !child.isTextual()) {
      throw new IllegalArgumentException("owner event field must be text: " + field);
    }
    return child.stringValue();
  }
}
