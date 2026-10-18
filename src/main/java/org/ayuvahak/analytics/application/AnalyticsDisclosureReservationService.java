package org.ayuvahak.analytics.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.DisclosureCandidate;
import org.ayuvahak.analytics.domain.DisclosureInferenceAnalyzer;
import org.ayuvahak.analytics.domain.DisclosurePolicyActivation;
import org.ayuvahak.analytics.domain.DisclosurePolicyVersion;
import org.ayuvahak.analytics.domain.DisclosureReservation;
import org.ayuvahak.analytics.domain.DisclosureReservationState;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.SourceKind;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorized release-set decisions and durable reservation lifecycle for Analytics outputs. */
@Service
public final class AnalyticsDisclosureReservationService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final DisclosureReservationStore reservations;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final NoSendEvidenceVerifier noSendEvidence;
  private final TransactionTemplate transactions;
  private final DisclosureInferenceAnalyzer inference = new DisclosureInferenceAnalyzer();
  private final Clock clock;

  public AnalyticsDisclosureReservationService(
      DisclosureReservationStore reservations,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock,
      NoSendEvidenceVerifier noSendEvidence) {
    this.reservations = Objects.requireNonNull(reservations, "disclosure reservation store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Objects.requireNonNull(clock, "audit clock");
    this.noSendEvidence = Objects.requireNonNull(noSendEvidence, "no-send evidence verifier");
  }

  public DisclosurePolicyVersion activatePolicy(
      ExecutionContext context, DisclosurePolicyVersion proposed) {
    Objects.requireNonNull(proposed, "disclosure policy");
    requireOrganizationScope(
        context,
        proposed.organization(),
        policyAuditId(proposed),
        "ANALYTICS_DISCLOSURE_POLICY_DENIED");
    requirePolicyAdministrator(context, policyAuditId(proposed));
    requireAuthorized(
        context,
        AnalyticsAuthorities.disclosurePolicyAdministration().code(),
        policyAuditId(proposed),
        "ANALYTICS_DISCLOSURE_POLICY_DENIED");

    Instant activatedAt = clock.instant();
    try {
      return transactions.execute(
          status -> {
            DisclosurePolicyActivation activation =
                reservations.activatePolicy(proposed, activatedAt);
            if (!activation.replayed()) {
              InternalId auditId =
                  audit.recordMutation(
                      mutationEvidence(
                          context,
                          "ANALYTICS_DISCLOSURE_POLICY_ACTIVATED",
                          policyAuditId(activation.policy()),
                          versionReason(activation.policy().version())));
              reservations.attachPolicyAudit(
                  activation.policy().policyKey(), activation.policy().version(), auditId.value());
            }
            return activation.policy();
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException failure) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.STORE_UNAVAILABLE, failure);
    }
  }

  public DisclosureReservation reserve(ExecutionContext context, DisclosureCandidate candidate) {
    Objects.requireNonNull(candidate, "disclosure candidate");
    DisclosurePolicyVersion policy = requireActivePolicy(candidate.policyKey());
    requireOrganizationScope(
        context,
        policy.organization(),
        candidate.operationId(),
        "ANALYTICS_DISCLOSURE_RELEASE_DENIED");
    requireReleaseWorker(context, candidate.operationId());
    requireAuthorized(
        context,
        AnalyticsAuthorities.disclosureRelease().code(),
        candidate.operationId(),
        "ANALYTICS_DISCLOSURE_RELEASE_DENIED");

    Instant decidedAt = clock.instant();
    try {
      return transactions.execute(
          status -> {
            DisclosureReleaseContext locked = reservations.lockForRelease(candidate);
            if (locked.replay().isPresent()) {
              return replay(locked.replay().orElseThrow());
            }
            DisclosureInferenceAnalyzer.Result result =
                inference.evaluate(
                    locked.policy(),
                    candidate,
                    locked.priorOutputs(),
                    locked.priorCellValues(),
                    locked.historyComplete());
            DisclosureReservationState state =
                switch (result) {
                  case SAFE -> DisclosureReservationState.RESERVED;
                  case INFERENCE_RISK -> DisclosureReservationState.WITHHELD;
                  case REVIEW_REQUIRED -> DisclosureReservationState.REVIEW_REQUIRED;
                };
            String reasonCode =
                switch (result) {
                  case SAFE -> "NONE";
                  case INFERENCE_RISK -> "INFERENCE_RISK";
                  case REVIEW_REQUIRED -> {
                    if (!locked.historyComplete()) {
                      yield "HISTORY_UNAVAILABLE";
                    }
                    yield locked.policy().externalBaselineComplete()
                        ? "INFERENCE_REVIEW_REQUIRED"
                        : "EXTERNAL_BASELINE_UNKNOWN";
                  }
                };
            DisclosureReservation reservation =
                reservations.recordDecision(candidate, locked, state, reasonCode, decidedAt);
            if (!reservation.replayed()) {
              InternalId auditId =
                  audit.recordMutation(
                      mutationEvidence(
                          context,
                          decisionAction(reservation.state()),
                          reservation.reservationId(),
                          reservation.reasonCode()));
              reservations.attachReservationAudit(reservation.reservationId(), auditId.value());
            }
            return reservation;
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException failure) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.STORE_UNAVAILABLE, failure);
    }
  }

  /** Records an administrator-confirmed, already-existing output as external release history. */
  public DisclosureReservation recordKnownExternalBaseline(
      ExecutionContext context, DisclosureCandidate baseline) {
    Objects.requireNonNull(baseline, "known external baseline");
    DisclosurePolicyVersion policy = requireActivePolicy(baseline.policyKey());
    requireOrganizationScope(
        context,
        policy.organization(),
        baseline.operationId(),
        "ANALYTICS_DISCLOSURE_BASELINE_DENIED");
    requirePolicyAdministrator(context, baseline.operationId());
    requireAuthorized(
        context,
        AnalyticsAuthorities.disclosurePolicyAdministration().code(),
        baseline.operationId(),
        "ANALYTICS_DISCLOSURE_BASELINE_DENIED");

    Instant recordedAt = clock.instant();
    try {
      return transactions.execute(
          status -> {
            DisclosureReleaseContext locked = reservations.lockForRelease(baseline);
            if (locked.replay().isPresent()) {
              return replay(locked.replay().orElseThrow());
            }
            DisclosureReservation result =
                reservations.recordKnownExternalBaseline(baseline, locked, recordedAt);
            InternalId auditId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        "ANALYTICS_DISCLOSURE_BASELINE_RECORDED",
                        result.reservationId(),
                        "KNOWN_EXTERNAL_BASELINE"));
            reservations.attachReservationAudit(result.reservationId(), auditId.value());
            return result;
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException failure) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.STORE_UNAVAILABLE, failure);
    }
  }

  public DisclosureReservation markDisclosed(ExecutionContext context, UUID reservationId) {
    return transition(
        context,
        reservationId,
        DisclosureReservationState.DISCLOSED,
        "ACKNOWLEDGED",
        Optional.empty());
  }

  public DisclosureReservation markPotentiallyDisclosed(
      ExecutionContext context, UUID reservationId) {
    return transition(
        context,
        reservationId,
        DisclosureReservationState.POTENTIALLY_DISCLOSED,
        "OUTCOME_UNKNOWN",
        Optional.empty());
  }

  public DisclosureReservation withdrawNotSent(
      ExecutionContext context, UUID reservationId, UUID outboundAttemptId) {
    Objects.requireNonNull(outboundAttemptId, "outbound attempt id");
    DisclosureReservation existing = requireReservation(reservationId);
    requireOrganizationScope(
        context,
        requireActivePolicy(existing.policyKey()).organization(),
        reservationId,
        "ANALYTICS_DISCLOSURE_LIFECYCLE_DENIED");
    requireReleaseWorker(context, reservationId);
    requireAuthorized(
        context,
        AnalyticsAuthorities.disclosureReservationLifecycle().code(),
        reservationId,
        "ANALYTICS_DISCLOSURE_LIFECYCLE_DENIED");
    if (!noSendEvidence.provesNoRequestBytesCouldHaveLeft(reservationId, outboundAttemptId)) {
      recordDenial(context, reservationId, "ANALYTICS_DISCLOSURE_WITHDRAWAL_DENIED");
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.NO_SEND_UNPROVEN);
    }
    return transitionAuthorized(
        context,
        reservationId,
        DisclosureReservationState.WITHDRAWN_NOT_SENT,
        "NO_REQUEST_BYTES_SENT");
  }

  public DisclosureReservation findReservation(ExecutionContext context, UUID reservationId) {
    DisclosureReservation reservation = requireReservation(reservationId);
    requireOrganizationScope(
        context,
        requireActivePolicy(reservation.policyKey()).organization(),
        reservationId,
        "ANALYTICS_DISCLOSURE_READ_DENIED");
    requireReleaseWorker(context, reservationId);
    requireAuthorized(
        context,
        AnalyticsAuthorities.disclosureReservationLifecycle().code(),
        reservationId,
        "ANALYTICS_DISCLOSURE_READ_DENIED");
    return reservation;
  }

  private DisclosureReservation transition(
      ExecutionContext context,
      UUID reservationId,
      DisclosureReservationState state,
      String reason,
      Optional<UUID> attemptId) {
    DisclosureReservation existing = requireReservation(reservationId);
    requireOrganizationScope(
        context,
        requireActivePolicy(existing.policyKey()).organization(),
        reservationId,
        "ANALYTICS_DISCLOSURE_LIFECYCLE_DENIED");
    requireReleaseWorker(context, reservationId);
    requireAuthorized(
        context,
        AnalyticsAuthorities.disclosureReservationLifecycle().code(),
        reservationId,
        "ANALYTICS_DISCLOSURE_LIFECYCLE_DENIED");
    return transitionAuthorized(context, reservationId, state, reason);
  }

  private DisclosureReservation transitionAuthorized(
      ExecutionContext context,
      UUID reservationId,
      DisclosureReservationState state,
      String reason) {
    Instant changedAt = clock.instant();
    try {
      return transactions.execute(
          status -> {
            DisclosureReservation changed =
                reservations.transition(reservationId, state, reason, changedAt);
            if (!changed.replayed()) {
              audit.recordMutation(
                  mutationEvidence(
                      context,
                      decisionAction(changed.state()),
                      changed.reservationId(),
                      changed.reasonCode()));
            }
            return changed;
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException failure) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.STORE_UNAVAILABLE, failure);
    }
  }

  private DisclosurePolicyVersion requireActivePolicy(String policyKey) {
    try {
      return reservations
          .activePolicy(policyKey)
          .orElseThrow(
              () ->
                  new DisclosureReservationException(
                      DisclosureReservationException.Reason.POLICY_NOT_FOUND));
    } catch (DataAccessException failure) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.STORE_UNAVAILABLE, failure);
    }
  }

  private DisclosureReservation requireReservation(UUID reservationId) {
    Objects.requireNonNull(reservationId, "reservation id");
    try {
      return reservations
          .findReservation(reservationId)
          .orElseThrow(
              () ->
                  new DisclosureReservationException(
                      DisclosureReservationException.Reason.RESERVATION_NOT_FOUND));
    } catch (DataAccessException failure) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.STORE_UNAVAILABLE, failure);
    }
  }

  private void requireOrganizationScope(
      ExecutionContext context,
      org.ayuvahak.platform.core.ExternalReference organization,
      UUID targetId,
      String denialAction) {
    if (context == null
        || context.scope().kind() != ExecutionScope.Kind.ORGANIZATION
        || context.scope().organization().filter(organization::equals).isEmpty()
        || !DIRECTORY_ISSUER.equals(organization.issuer().value())
        || !"Organization".equals(organization.resourceType())) {
      recordDenial(context, targetId, denialAction);
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.NOT_AUTHORIZED);
    }
  }

  private void requirePolicyAdministrator(ExecutionContext context, UUID targetId) {
    if (context == null
        || context.actor().kind() != ActorKind.HUMAN
        || !(context.sourceKind() == SourceKind.INTERACTIVE_REQUEST
            || context.sourceKind() == SourceKind.LOCAL_OPERATOR_COMMAND)) {
      recordDenial(context, targetId, "ANALYTICS_DISCLOSURE_POLICY_DENIED");
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.NOT_AUTHORIZED);
    }
  }

  private void requireReleaseWorker(ExecutionContext context, UUID targetId) {
    if (context == null
        || context.actor().kind() != ActorKind.SERVICE
        || !(context.sourceKind() == SourceKind.INTEGRATION
            || context.sourceKind() == SourceKind.BACKGROUND_WORK
            || context.sourceKind() == SourceKind.SCHEDULED_WORK)) {
      recordDenial(context, targetId, "ANALYTICS_DISCLOSURE_RELEASE_DENIED");
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.NOT_AUTHORIZED);
    }
  }

  private void requireAuthorized(
      ExecutionContext context, String authority, UUID targetId, String deniedAction) {
    if (authorization.evaluate(context, authority).isAllowed()) {
      return;
    }
    recordDenial(context, targetId, deniedAction);
    throw new DisclosureReservationException(DisclosureReservationException.Reason.NOT_AUTHORIZED);
  }

  private void recordDenial(ExecutionContext context, UUID targetId, String action) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.ofNullable(context).map(ExecutionContext::actor),
              Optional.ofNullable(context).map(ExecutionContext::scope),
              action,
              "AggregateDisclosureReservation",
              Optional.of(new InternalId(targetId)),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of("AUTHORIZATION_OR_STATE_DENIED"),
              authenticationEvidence(context),
              context == null
                  ? AuditAuthorizationOutcome.NOT_EVALUATED
                  : AuditAuthorizationOutcome.DENIED,
              context == null ? new CorrelationId(targetId.toString()) : context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new DisclosureReservationException(
          DisclosureReservationException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private AuditEvidence mutationEvidence(
      ExecutionContext context, String action, UUID targetId, String reason) {
    return new AuditEvidence(
        AuditActionKind.PROTECTED_MUTATION,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        action,
        "AggregateDisclosureReservation",
        Optional.of(new InternalId(targetId)),
        clock.instant(),
        AuditOutcome.SUCCEEDED,
        Optional.of(reason),
        authenticationEvidence(context),
        AuditAuthorizationOutcome.ALLOWED,
        context.correlationId());
  }

  private static String decisionAction(DisclosureReservationState state) {
    return switch (state) {
      case RESERVED -> "ANALYTICS_DISCLOSURE_RESERVED";
      case DISCLOSED -> "ANALYTICS_DISCLOSURE_ACKNOWLEDGED";
      case POTENTIALLY_DISCLOSED -> "ANALYTICS_DISCLOSURE_OUTCOME_UNKNOWN";
      case WITHDRAWN_NOT_SENT -> "ANALYTICS_DISCLOSURE_WITHDRAWN_NOT_SENT";
      case WITHHELD -> "ANALYTICS_DISCLOSURE_WITHHELD";
      case REVIEW_REQUIRED -> "ANALYTICS_DISCLOSURE_REVIEW_REQUIRED";
    };
  }

  private static UUID policyAuditId(DisclosurePolicyVersion policy) {
    Objects.requireNonNull(policy, "policy");
    return InternalId.generate().value();
  }

  private static String versionReason(int version) {
    return "VERSION_" + version;
  }

  private static DisclosureReservation replay(DisclosureReservation reservation) {
    return new DisclosureReservation(
        reservation.reservationId(),
        reservation.policyKey(),
        reservation.policyVersion(),
        reservation.operationId(),
        reservation.operationFingerprint(),
        reservation.lineageIds(),
        reservation.historyCheckpoint(),
        reservation.state(),
        reservation.reasonCode(),
        reservation.createdAt(),
        reservation.updatedAt(),
        true);
  }

  private static AuditAuthenticationEvidence authenticationEvidence(ExecutionContext context) {
    if (context == null) {
      return AuditAuthenticationEvidence.NONE;
    }
    AuthenticationMechanism mechanism = context.authenticationMechanism();
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }
}
