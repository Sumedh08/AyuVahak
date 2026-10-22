package org.ayuvahak.analytics.application;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.SourceKind;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Audited publication of immutable Analytics-owned indicator definition versions. */
@Service
public final class AnalyticsIndicatorDefinitionAdministrationService {

  private final AnalyticsStore store;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public AnalyticsIndicatorDefinitionAdministrationService(
      AnalyticsStore store,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.store = Objects.requireNonNull(store, "Analytics store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public IndicatorDefinitionVersion publish(
      ExecutionContext context, IndicatorDefinitionVersion proposed) {
    Objects.requireNonNull(proposed, "indicator definition");
    requireAuthorizedAdministrator(context);
    try {
      return transactions.execute(
          status -> {
            Optional<IndicatorDefinitionVersion> existing =
                store.findDefinition(proposed.key(), proposed.version());
            if (existing.isPresent()) {
              if (existing.orElseThrow().fingerprint().equals(proposed.fingerprint())) {
                return existing.orElseThrow();
              }
              throw new AnalyticsDefinitionConflictException();
            }
            IndicatorDefinitionVersion published = store.publishDefinition(proposed);
            audit.recordMutation(
                new AuditEvidence(
                    AuditActionKind.PROTECTED_MUTATION,
                    Optional.of(context.actor()),
                    Optional.of(context.scope()),
                    "ANALYTICS_INDICATOR_DEFINITION_PUBLISHED",
                    "IndicatorDefinitionVersion",
                    Optional.of(InternalId.generate()),
                    clock.instant(),
                    AuditOutcome.SUCCEEDED,
                    Optional.of("VERSION_PUBLISHED"),
                    authenticationEvidence(context.authenticationMechanism()),
                    AuditAuthorizationOutcome.ALLOWED,
                    context.correlationId()));
            return published;
          });
    } catch (AnalyticsDefinitionConflictException conflict) {
      throw conflictProblem();
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Analytics could not publish the indicator version safely.");
    }
  }

  private void requireAuthorizedAdministrator(ExecutionContext context) {
    AuthorizationDecision decision =
        context == null
            ? AuthorizationDecision.deny(
                AuthorizationDecision.DenialReason.EXECUTION_CONTEXT_REQUIRED)
            : authorization.evaluate(
                context, AnalyticsAuthorities.indicatorDefinitionAdministration().code());
    if (context != null
        && context.actor().kind() == ActorKind.HUMAN
        && context.sourceKind() == SourceKind.INTERACTIVE_REQUEST
        && context.scope().kind() == ExecutionScope.Kind.PLATFORM
        && decision.isAllowed()) {
      return;
    }
    recordDenial(context);
    throw problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The actor is not authorized to publish Analytics indicator definitions.");
  }

  private void recordDenial(ExecutionContext context) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.ofNullable(context).map(ExecutionContext::actor),
              Optional.ofNullable(context).map(ExecutionContext::scope),
              "ANALYTICS_INDICATOR_DEFINITION_DENIED",
              "IndicatorDefinitionVersion",
              Optional.of(InternalId.generate()),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of("AUTHORIZATION_OR_STATE_DENIED"),
              context == null
                  ? AuditAuthenticationEvidence.NONE
                  : authenticationEvidence(context.authenticationMechanism()),
              context == null
                  ? AuditAuthorizationOutcome.NOT_EVALUATED
                  : AuditAuthorizationOutcome.DENIED,
              context == null
                  ? new CorrelationId(UUID.randomUUID().toString())
                  : context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Analytics administration is unavailable because its required security audit could not be stored.");
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

  private static PlatformProblemException conflictProblem() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.REQUEST_FAILED,
        "The indicator version conflicts with its immutable definition history.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
