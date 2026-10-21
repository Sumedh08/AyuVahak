package org.ayuvahak.analytics.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
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
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Authorized read-only Analytics view; source facts remain with their owner contexts. */
@Service
public final class AnalyticsSnapshotReader {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_PAGE = 100_000;

  private final AnalyticsSnapshotReadStore store;
  private final AuthorizationService authorization;
  private final AuditWriter auditWriter;
  private final Clock clock;

  public AnalyticsSnapshotReader(
      AnalyticsSnapshotReadStore store,
      AuthorizationService authorization,
      AuditWriter auditWriter,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "snapshot read store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public AnalyticsSnapshotPage list(ExecutionContext context, int page, int size) {
    if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.VALIDATION_FAILED,
          "Analytics views require a page between 0 and 100000 and a page size between 1 and 100.");
    }
    ExecutionScope scope = validatedScope(context);
    AuthorizationDecision decision =
        authorization.evaluate(context, AnalyticsAuthorities.aggregateRead().code());
    if (!decision.isAllowed()) {
      boolean unavailable =
          decision.denialReason().orElse(null)
              == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
      throw problem(
          unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
          unavailable
              ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
              : PlatformErrorCode.AUTHORIZATION_DENIED,
          unavailable
              ? "Analytics authorization is temporarily unavailable."
              : "The actor is not authorized to view these aggregate snapshots.");
    }
    ExternalReference organization = scope.organization().orElseThrow();
    List<AnalyticsSnapshotSummary> window =
        store.findRecent(organization, scope.facility(), Math.multiplyExact(page, size), size + 1);
    boolean hasMore = window.size() > size;
    List<AnalyticsSnapshotSummary> items = hasMore ? window.subList(0, size) : window;
    recordRead(context, scope);
    return new AnalyticsSnapshotPage(items, page, size, hasMore);
  }

  private static ExecutionScope validatedScope(ExecutionContext context) {
    if (context == null
        || context.sourceKind() != org.ayuvahak.platform.core.SourceKind.INTERACTIVE_REQUEST
        || context.actor().kind() != ActorKind.HUMAN
        || context.scope() == null) {
      throw invalidScope();
    }
    ExecutionScope scope = context.scope();
    ExternalReference organization = scope.organization().orElse(null);
    boolean valid =
        (scope.kind() == ExecutionScope.Kind.ORGANIZATION
                || scope.kind() == ExecutionScope.Kind.FACILITY)
            && organization != null
            && DIRECTORY_ISSUER.equals(organization.issuer().value())
            && "Organization".equals(organization.resourceType());
    if (scope.kind() == ExecutionScope.Kind.FACILITY) {
      ExternalReference facility = scope.facility().orElse(null);
      valid &=
          facility != null
              && DIRECTORY_ISSUER.equals(facility.issuer().value())
              && "Facility".equals(facility.resourceType());
    }
    if (!valid) throw invalidScope();
    return scope;
  }

  private void recordRead(ExecutionContext context, ExecutionScope scope) {
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SENSITIVE_READ,
              Optional.of(context.actor()),
              Optional.of(scope),
              "ANALYTICS_SNAPSHOT_LIST_READ",
              "AggregateSnapshotCollection",
              Optional.empty(),
              clock.instant(),
              AuditOutcome.SUCCEEDED,
              Optional.empty(),
              authenticationEvidence(context.authenticationMechanism()),
              AuditAuthorizationOutcome.ALLOWED,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(scope.kind(), scope.organization(), scope.facility()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Analytics information is unavailable because its required access audit could not be stored.");
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

  private static PlatformProblemException invalidScope() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Analytics views require a validated Directory organization or facility scope.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
