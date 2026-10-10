package org.ayuvahak.referral.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Origin-scoped Referral reads with durable audit-before-disclosure behavior. */
@Service
@Lazy
public class ReferralQueries {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final ReferralDraftStore drafts;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate readTransaction;
  private final Clock clock;

  public ReferralQueries(
      @Lazy ReferralDraftStore drafts,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.drafts = Objects.requireNonNull(drafts, "Referral store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.clock = Objects.requireNonNull(clock, "audit clock");
    readTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    readTransaction.setReadOnly(true);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  public ReferralCurrentView current(ExecutionContext context, InternalId referralId) {
    requireOriginScope(context);
    requireReadAuthority(context);
    Optional<ReferralCurrentView> found;
    try {
      found =
          Objects.requireNonNull(
              readTransaction.execute(
                  ignored ->
                      drafts
                          .findDraft(referralId)
                          .map(
                              draft ->
                                  new ReferralCurrentView(
                                      draft, drafts.findLatestSubmittedRevision(referralId)))),
              "Referral read transaction result");
    } catch (DataAccessException unavailable) {
      throw unavailable("Referral detail could not be read safely.");
    }
    if (found.isEmpty()) {
      recordOutcome(
          context,
          "REFERRAL_DETAIL_NOT_FOUND",
          Optional.of(referralId),
          AuditOutcome.FAILED,
          AuditAuthorizationOutcome.ALLOWED,
          Optional.of("REFERRAL_NOT_FOUND"));
      throw notFound();
    }
    ReferralCurrentView view = found.orElseThrow();
    if (!originMatches(context.scope(), view.current())) {
      recordOutcome(
          context,
          "REFERRAL_DETAIL_SCOPE_REJECTED",
          Optional.of(referralId),
          AuditOutcome.DENIED,
          AuditAuthorizationOutcome.DENIED,
          Optional.of("REFERRAL_SCOPE_MISMATCH"));
      throw notFound();
    }
    recordRead(context, "REFERRAL_DETAIL_READ", Optional.of(referralId));
    return view;
  }

  public ReferralCurrentView revision(
      ExecutionContext context, InternalId referralId, long revisionNumber) {
    requireOriginScope(context);
    requireReadAuthority(context);
    if (revisionNumber < 1) {
      throw invalid("A positive submitted Referral revision number is required.");
    }
    Optional<ReferralCurrentView> found;
    try {
      found =
          Objects.requireNonNull(
              readTransaction.execute(
                  ignored -> {
                    Optional<org.ayuvahak.referral.domain.ReferralDraft> current =
                        drafts.findDraft(referralId);
                    if (current.isEmpty()) {
                      return Optional.empty();
                    }
                    return Optional.of(
                        new ReferralCurrentView(
                            current.orElseThrow(),
                            drafts.findSubmittedRevisionNumber(referralId, revisionNumber)));
                  }),
              "Referral revision transaction result");
    } catch (DataAccessException unavailable) {
      throw unavailable("Referral revision could not be read safely.");
    }
    if (found.isEmpty()) {
      recordOutcome(
          context,
          "REFERRAL_REVISION_NOT_FOUND",
          Optional.of(referralId),
          AuditOutcome.FAILED,
          AuditAuthorizationOutcome.ALLOWED,
          Optional.of("REFERRAL_NOT_FOUND"));
      throw notFound();
    }
    ReferralCurrentView view = found.orElseThrow();
    if (!originMatches(context.scope(), view.current())) {
      recordOutcome(
          context,
          "REFERRAL_REVISION_SCOPE_REJECTED",
          Optional.of(referralId),
          AuditOutcome.DENIED,
          AuditAuthorizationOutcome.DENIED,
          Optional.of("REFERRAL_SCOPE_MISMATCH"));
      throw notFound();
    }
    if (view.submittedRevision().isEmpty()) {
      recordOutcome(
          context,
          "REFERRAL_REVISION_NOT_FOUND",
          Optional.of(referralId),
          AuditOutcome.FAILED,
          AuditAuthorizationOutcome.ALLOWED,
          Optional.of("REFERRAL_NOT_FOUND"));
      throw notFound();
    }
    recordRead(context, "REFERRAL_REVISION_READ", Optional.of(referralId));
    return view;
  }

  /**
   * Returns only the fields needed by Matching for the exact current submitted revision. The
   * PatientReference and the full Referral aggregate never cross this owner boundary.
   */
  public ReferralMatchingRequirement getSubmittedRequirement(
      ExecutionContext context, InternalId referralId, long revisionNumber) {
    requireOriginScope(context);
    requireReadAuthority(context);
    if (revisionNumber < 1) {
      throw invalid("A positive submitted Referral revision number is required.");
    }

    Optional<SubmittedRevisionRead> found;
    try {
      found =
          Objects.requireNonNull(
              readTransaction.execute(
                  ignored -> {
                    Optional<org.ayuvahak.referral.domain.ReferralDraft> current =
                        drafts.findDraft(referralId);
                    if (current.isEmpty()) {
                      return Optional.empty();
                    }
                    Optional<org.ayuvahak.referral.domain.SubmittedReferralRevision> requested =
                        drafts.findSubmittedRevisionNumber(referralId, revisionNumber);
                    if (requested.isEmpty()) {
                      return Optional.empty();
                    }
                    return Optional.of(
                        new SubmittedRevisionRead(
                            current.orElseThrow(),
                            requested.orElseThrow(),
                            drafts.findLatestSubmittedRevision(referralId)));
                  }),
              "Matching Referral read transaction result");
    } catch (DataAccessException unavailable) {
      throw unavailable("Referral requirement could not be read safely for Matching.");
    }

    if (found.isEmpty()) {
      recordOutcome(
          context,
          "REFERRAL_MATCHING_REQUIREMENT_NOT_FOUND",
          Optional.of(referralId),
          AuditOutcome.FAILED,
          AuditAuthorizationOutcome.ALLOWED,
          Optional.of("REFERRAL_NOT_FOUND"));
      throw notFound();
    }

    SubmittedRevisionRead read = found.orElseThrow();
    var current = read.current();
    var requested = read.requested();
    if (!originMatches(context.scope(), current)
        || !current.originOrganization().equals(requested.originOrganization())
        || !current.originFacility().equals(requested.originFacility())) {
      recordOutcome(
          context,
          "REFERRAL_MATCHING_REQUIREMENT_SCOPE_REJECTED",
          Optional.of(referralId),
          AuditOutcome.DENIED,
          AuditAuthorizationOutcome.DENIED,
          Optional.of("REFERRAL_SCOPE_MISMATCH"));
      throw notFound();
    }

    boolean exactCurrentSubmission =
        current.state() == org.ayuvahak.referral.domain.ReferralState.SUBMITTED
            && current.version() == requested.referralVersion()
            && read.latest()
                    .map(org.ayuvahak.referral.domain.SubmittedReferralRevision::revisionNumber)
                    .orElse(-1L)
                == revisionNumber;
    if (!exactCurrentSubmission) {
      recordOutcome(
          context,
          "REFERRAL_MATCHING_REQUIREMENT_STALE",
          Optional.of(referralId),
          AuditOutcome.FAILED,
          AuditAuthorizationOutcome.ALLOWED,
          Optional.of("REFERRAL_REVISION_STALE"));
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.REFERRAL_VERSION_CONFLICT,
          "The submitted Referral revision is no longer current for Matching.");
    }

    recordOutcome(
        context,
        "REFERRAL_MATCHING_REQUIREMENT_READ",
        Optional.of(referralId),
        AuditOutcome.SUCCEEDED,
        AuditAuthorizationOutcome.ALLOWED,
        Optional.of("MATCHING_EVALUATION"));
    var requirement = requested.careRequirement();
    return new ReferralMatchingRequirement(
        requested.referralId(),
        requested.revisionNumber(),
        current.version(),
        requested.originOrganization(),
        requested.originFacility(),
        requirement.requiredCareCapabilityCodes(),
        requirement.requestedStart(),
        requirement.requestedEnd(),
        requirement.permittedGeographyCodes(),
        requirement.selectedDestinationOrganization(),
        requirement.priorityCode(),
        requested.submittedAt());
  }

  private record SubmittedRevisionRead(
      org.ayuvahak.referral.domain.ReferralDraft current,
      org.ayuvahak.referral.domain.SubmittedReferralRevision requested,
      Optional<org.ayuvahak.referral.domain.SubmittedReferralRevision> latest) {}

  public ReferralListPage list(ExecutionContext context, ReferralReadFilter filter) {
    requireOriginScope(context);
    requireReadAuthority(context);
    Objects.requireNonNull(filter, "Referral list filter");
    List<ReferralListItem> rows;
    try {
      rows =
          Objects.requireNonNull(
              readTransaction.execute(
                  ignored ->
                      drafts.findReferralPage(
                          context.scope(),
                          filter,
                          filter.size() + 1,
                          (long) filter.page() * filter.size())),
              "Referral list transaction result");
    } catch (DataAccessException unavailable) {
      throw unavailable("Referral list could not be read safely.");
    }
    boolean hasMore = rows.size() > filter.size();
    List<ReferralListItem> items = hasMore ? rows.subList(0, filter.size()) : rows;
    recordRead(context, "REFERRAL_LIST_READ", Optional.empty());
    return new ReferralListPage(items, filter.page(), filter.size(), hasMore);
  }

  public void recordInvalidScope(ActorReference actor, CorrelationId correlationId) {
    recordOutcome(
        actor,
        Optional.empty(),
        "REFERRAL_SCOPE_REJECTED",
        Optional.empty(),
        AuditOutcome.DENIED,
        AuditAuthorizationOutcome.NOT_EVALUATED,
        correlationId,
        authentication(actor.kind()),
        Optional.of("INVALID_EXECUTION_SCOPE"));
  }

  private void requireReadAuthority(ExecutionContext context) {
    AuthorizationDecision decision =
        authorization
            .getObject()
            .evaluate(context, org.ayuvahak.referral.domain.ReferralAuthorities.read().code());
    if (decision.isAllowed()) {
      return;
    }
    recordOutcome(
        context,
        "REFERRAL_READ_DENIED",
        Optional.empty(),
        AuditOutcome.DENIED,
        AuditAuthorizationOutcome.DENIED,
        Optional.of("AUTHORIZATION_DENIED"));
    if (decision.denialReason().orElse(null)
        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Referral access could not be authorized safely.");
    }
    throw problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated actor is not authorized to read this Referral.");
  }

  private void requireOriginScope(ExecutionContext context) {
    Objects.requireNonNull(context, "execution context");
    ExecutionScope scope = context.scope();
    if ((scope.kind() != ExecutionScope.Kind.ORGANIZATION
            && scope.kind() != ExecutionScope.Kind.FACILITY)
        || scope.organization().isEmpty()
        || !DIRECTORY_ISSUER.equals(scope.organization().orElseThrow().issuer().value())
        || !"Organization".equals(scope.organization().orElseThrow().resourceType())
        || (scope.kind() == ExecutionScope.Kind.FACILITY
            && (scope.facility().isEmpty()
                || !DIRECTORY_ISSUER.equals(scope.facility().orElseThrow().issuer().value())
                || !"Facility".equals(scope.facility().orElseThrow().resourceType())))) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Referral reads require a validated Directory origin scope.");
    }
    try {
      InternalId.parse(scope.organization().orElseThrow().value());
      scope.facility().ifPresent(reference -> InternalId.parse(reference.value()));
    } catch (IllegalArgumentException malformed) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Referral reads require a valid Directory origin scope.");
    }
  }

  private static boolean originMatches(
      ExecutionScope scope, org.ayuvahak.referral.domain.ReferralDraft draft) {
    return scope.organization().equals(Optional.of(draft.originOrganization()))
        && (scope.kind() == ExecutionScope.Kind.ORGANIZATION
            || scope.facility().equals(draft.originFacility()));
  }

  private void recordRead(
      ExecutionContext context, String actionCode, Optional<InternalId> target) {
    recordOutcome(
        context,
        actionCode,
        target,
        AuditOutcome.SUCCEEDED,
        AuditAuthorizationOutcome.ALLOWED,
        Optional.empty());
  }

  private void recordOutcome(
      ExecutionContext context,
      String actionCode,
      Optional<InternalId> target,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome,
      Optional<String> reasonCode) {
    recordOutcome(
        context.actor(),
        Optional.of(context.scope()),
        actionCode,
        target,
        outcome,
        authorizationOutcome,
        context.correlationId(),
        authentication(context.authenticationMechanism()),
        reasonCode);
  }

  private void recordOutcome(
      ActorReference actor,
      Optional<ExecutionScope> scope,
      String actionCode,
      Optional<InternalId> target,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome,
      CorrelationId correlationId,
      AuditAuthenticationEvidence authentication,
      Optional<String> reasonCode) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  outcome == AuditOutcome.SUCCEEDED
                      ? AuditActionKind.SENSITIVE_READ
                      : AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(actor),
                  scope,
                  actionCode,
                  "Referral",
                  target,
                  clock.instant(),
                  outcome,
                  reasonCode,
                  authentication,
                  authorizationOutcome,
                  correlationId,
                  scope.map(
                      executionScope ->
                          new AuditTargetContext(
                              executionScope.kind(),
                              executionScope.organization(),
                              executionScope.facility()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Referral read outcome could not be recorded durably.");
    }
  }

  private static AuditAuthenticationEvidence authentication(AuthenticationMechanism mechanism) {
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static AuditAuthenticationEvidence authentication(ActorKind kind) {
    return kind == ActorKind.HUMAN
        ? AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN
        : AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
  }

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.REFERRAL_NOT_FOUND,
        "The Referral does not exist in the authorized origin scope.");
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.REFERRAL_REQUIREMENT_INVALID, detail);
  }

  private static PlatformProblemException unavailable(String detail) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, PlatformErrorCode.REFERRAL_UNAVAILABLE, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
