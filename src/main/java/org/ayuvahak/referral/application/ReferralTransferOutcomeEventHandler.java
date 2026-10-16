package org.ayuvahak.referral.application;

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
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralResolution;
import org.ayuvahak.referral.domain.ReferralResolutionOutcome;
import org.ayuvahak.referral.domain.ReferralState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Referral-owned idempotent reaction to Transfer completion and unfulfilled closure. */
@Component
@Lazy
public final class ReferralTransferOutcomeEventHandler implements InternalEventHandler {

  private static final EventType COMPLETED = EventType.parse("transfer.completed.v1");
  private static final EventType UNFULFILLED = EventType.parse("transfer.unfulfilled.v1");

  private final ReferralDraftStore drafts;
  private final ExecutionContextFactory contexts;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final TransactionTemplate denialTransaction;
  private final ActorReference worker;
  private final Clock clock;
  private final ObjectMapper mapper;

  public ReferralTransferOutcomeEventHandler(
      @Lazy ReferralDraftStore drafts,
      ExecutionContextFactory contexts,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      ReferralSettings properties,
      AuthenticationProperties authentication,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock,
      ObjectMapper mapper) {
    this.drafts = drafts;
    this.contexts = contexts;
    this.authorization = authorization;
    this.auditWriter = auditWriter;
    this.transaction = new TransactionTemplate(transactionManager);
    this.denialTransaction = new TransactionTemplate(transactionManager);
    this.denialTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    worker =
        ActorReference.service(
            new IssuerId(authentication.issuer()), properties.getTransferOutcomeConsumerClientId());
    this.clock = clock;
    this.mapper = mapper;
  }

  @Override
  public Set<EventType> supportedEventTypes() {
    return Set.of(COMPLETED, UNFULFILLED);
  }

  @Override
  public void handle(InternalEvent event) throws Exception {
    if (drafts.isConsumerEventCompleted(event.eventId(), event.eventType())) {
      return;
    }
    JsonNode envelope = mapper.readTree(event.envelopeJson());
    JsonNode payload = object(envelope, "payload");
    InternalId referralId = InternalId.parse(text(payload, "referralReference"));
    if (event.eventType().equals(COMPLETED)) {
      requireOutcome(payload, "COMPLETED");
      InternalId.parse(text(payload, "transferReference"));
    } else if (event.eventType().equals(UNFULFILLED)) {
      requireOutcome(payload, "UNFULFILLED");
      if (payload.has("transferReference")) {
        InternalId.parse(text(payload, "transferReference"));
      } else {
        InternalId.parse(text(payload, "journeyReference"));
      }
    } else {
      throw new IllegalArgumentException("unsupported Transfer outcome event");
    }
    Instant occurredAt = Instant.parse(text(envelope, "occurredAt"));
    try {
      transaction.executeWithoutResult(status -> apply(event, envelope, referralId, occurredAt));
    } catch (AuthorizationRejected denied) {
      denialTransaction.executeWithoutResult(status -> auditDenied(denied));
      throw denied;
    }
  }

  private void apply(
      InternalEvent event, JsonNode envelope, InternalId referralId, Instant occurredAt) {
    Instant recordedAt = clock.instant();
    if (!drafts.claimConsumerEvent(event.eventId(), event.eventType(), recordedAt)) {
      return;
    }
    ReferralDraft current = drafts.lockDraft(referralId).orElse(null);
    ExecutionContext context =
        current == null
            ? contextFromEnvelope(envelope, event.eventId())
            : contextFor(current, envelope, event.eventId());
    var decision =
        authorization.getObject().evaluate(context, ReferralAuthorities.resolve().code());
    if (!decision.isAllowed()) {
      throw new AuthorizationRejected(context, referralId, decision);
    }
    if (current == null) {
      auditNoState(context, referralId, "REFERRAL_TRANSFER_OUTCOME_NO_REFERRAL");
      drafts.completeConsumerEvent(event.eventId(), referralId, "NO_REFERRAL", recordedAt);
      return;
    }
    if (current.state() != ReferralState.SUBMITTED) {
      auditNoState(context, referralId, "REFERRAL_TRANSFER_OUTCOME_TERMINAL_NO_CHANGE");
      drafts.completeConsumerEvent(
          event.eventId(), referralId, "TERMINAL_NO_STATE_CHANGE", recordedAt);
      return;
    }
    ReferralResolutionOutcome outcome =
        event.eventType().equals(COMPLETED)
            ? ReferralResolutionOutcome.FULFILLED
            : ReferralResolutionOutcome.UNFULFILLED;
    String reasonCode =
        outcome == ReferralResolutionOutcome.FULFILLED
            ? "TRANSFER_COMPLETED"
            : "TRANSFER_UNFULFILLED";
    ReferralDraft resolved = current.resolve(worker, occurredAt);
    ReferralResolution resolution =
        new ReferralResolution(
            referralId, outcome, reasonCode, worker, recordedAt, resolved.version());
    if (!drafts.resolveDraft(resolved, current.version(), resolution)) {
      throw new IllegalStateException("Referral changed while applying Transfer outcome");
    }
    auditMutation(context, referralId, outcome);
    drafts.completeConsumerEvent(event.eventId(), referralId, outcome.name(), recordedAt);
  }

  private ExecutionContext contextFor(ReferralDraft draft, JsonNode envelope, InternalId eventId) {
    RequestedExecutionScope scope =
        draft
            .originFacility()
            .<RequestedExecutionScope>map(
                facility -> RequestedExecutionScope.facility(draft.originOrganization(), facility))
            .orElseGet(() -> RequestedExecutionScope.organization(draft.originOrganization()));
    return background(scope, envelope, eventId);
  }

  private ExecutionContext contextFromEnvelope(JsonNode envelope, InternalId eventId) {
    JsonNode scope = object(envelope, "executionScope");
    ExternalReference organization = reference(object(scope, "organizationRef"));
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

  private void auditMutation(
      ExecutionContext context, InternalId referralId, ReferralResolutionOutcome outcome) {
    auditWriter
        .getObject()
        .recordMutation(
            new AuditEvidence(
                AuditActionKind.PROTECTED_MUTATION,
                Optional.of(worker),
                Optional.of(context.scope()),
                "REFERRAL_TRANSFER_OUTCOME_RECONCILE",
                "Referral",
                Optional.of(referralId),
                clock.instant(),
                AuditOutcome.SUCCEEDED,
                Optional.of(outcome.name()),
                AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId(),
                Optional.of(targetContext(context))));
  }

  private void auditNoState(ExecutionContext context, InternalId referralId, String action) {
    auditWriter
        .getObject()
        .recordMutation(
            new AuditEvidence(
                AuditActionKind.PROTECTED_MUTATION,
                Optional.of(worker),
                Optional.of(context.scope()),
                action,
                "Referral",
                Optional.of(referralId),
                clock.instant(),
                AuditOutcome.SUCCEEDED,
                Optional.of("NO_STATE_CHANGE"),
                AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId(),
                Optional.of(targetContext(context))));
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
                "REFERRAL_TRANSFER_OUTCOME_DENIED",
                "Referral",
                Optional.of(denied.referralId()),
                clock.instant(),
                AuditOutcome.DENIED,
                Optional.of(denialCode(denied.decision())),
                AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY,
                AuditAuthorizationOutcome.DENIED,
                context.correlationId(),
                Optional.of(targetContext(context))));
  }

  private static AuditTargetContext targetContext(ExecutionContext context) {
    return new AuditTargetContext(
        context.scope().kind(), context.scope().organization(), context.scope().facility());
  }

  private static String denialCode(AuthorizationDecision decision) {
    return decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
        ? "AUTHORIZATION_STORE_UNAVAILABLE"
        : "AUTHORIZATION_DENIED";
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
      throw new IllegalArgumentException("Transfer event must contain an object field: " + field);
    }
    return child;
  }

  private static String text(JsonNode value, String field) {
    JsonNode child = value.get(field);
    if (child == null || !child.isTextual()) {
      throw new IllegalArgumentException("Transfer event field must be text: " + field);
    }
    return child.stringValue();
  }

  private static void requireOutcome(JsonNode payload, String expectedOutcome) {
    if (!expectedOutcome.equals(text(payload, "outcome"))) {
      throw new IllegalArgumentException("Transfer outcome does not match its event type");
    }
  }

  private static final class AuthorizationRejected extends RuntimeException {

    private final ExecutionContext context;
    private final InternalId referralId;
    private final AuthorizationDecision decision;

    private AuthorizationRejected(
        ExecutionContext context, InternalId referralId, AuthorizationDecision decision) {
      super("Referral outcome event consumer is not authorized");
      this.context = context;
      this.referralId = referralId;
      this.decision = decision;
    }

    private ExecutionContext context() {
      return context;
    }

    private InternalId referralId() {
      return referralId;
    }

    private AuthorizationDecision decision() {
      return decision;
    }
  }
}
