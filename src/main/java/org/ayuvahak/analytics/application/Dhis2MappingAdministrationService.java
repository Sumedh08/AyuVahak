package org.ayuvahak.analytics.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.Dhis2MappingVersion;
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

/** Human-authorized publication of immutable Analytics-owned mappings to preconfigured targets. */
@Service
public final class Dhis2MappingAdministrationService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final Dhis2MappingStore mappings;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public Dhis2MappingAdministrationService(
      Dhis2MappingStore mappings,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.mappings = Objects.requireNonNull(mappings, "DHIS2 mapping store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public Dhis2MappingVersion publish(ExecutionContext context, Dhis2MappingVersion proposed) {
    Objects.requireNonNull(proposed, "DHIS2 mapping");
    requireAdministrator(context, proposed.organization(), auditId());
    Instant now = clock.instant();
    try {
      return transactions.execute(
          status -> {
            Dhis2MappingStore.PublishResult result = mappings.publish(proposed, now);
            if (!result.created()) {
              return result.mapping();
            }
            InternalId auditId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        "ANALYTICS_DHIS2_MAPPING_PUBLISHED",
                        auditId(),
                        "VERSION_" + proposed.version()));
            mappings.attachAudit(proposed.mappingKey(), proposed.version(), auditId.value());
            mappings.attachLifecycleAudit(proposed.mappingKey(), auditId.value());
            return result.mapping();
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  public void deactivate(ExecutionContext context, String mappingKey) {
    UUID targetId = auditId();
    var current =
        mappings
            .findVersion(mappingKey, activeVersion(mappingKey))
            .orElseThrow(() -> new Dhis2MappingException(Dhis2MappingException.Reason.NOT_FOUND));
    requireAdministrator(context, current.organization(), targetId);
    Instant now = clock.instant();
    try {
      transactions.execute(
          status -> {
            mappings.deactivate(mappingKey, now);
            InternalId auditId =
                audit.recordMutation(
                    mutationEvidence(
                        context, "ANALYTICS_DHIS2_MAPPING_DEACTIVATED", targetId, "INACTIVE"));
            mappings.attachLifecycleAudit(mappingKey, auditId.value());
            return null;
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private int activeVersion(String mappingKey) {
    // The head lookup intentionally exposes only an integer version, not target metadata.
    return mappings
        .findActiveVersion(mappingKey)
        .orElseThrow(() -> new Dhis2MappingException(Dhis2MappingException.Reason.NOT_FOUND));
  }

  private void requireAdministrator(
      ExecutionContext context,
      org.ayuvahak.platform.core.ExternalReference organization,
      UUID targetId) {
    if (context == null
        || context.actor().kind() != ActorKind.HUMAN
        || !(context.sourceKind() == SourceKind.INTERACTIVE_REQUEST
            || context.sourceKind() == SourceKind.LOCAL_OPERATOR_COMMAND)
        || context.scope().kind() != ExecutionScope.Kind.ORGANIZATION
        || context.scope().organization().filter(organization::equals).isEmpty()
        || !DIRECTORY_ISSUER.equals(organization.issuer().value())
        || !authorization
            .evaluate(context, AnalyticsAuthorities.dhis2MappingAdministration().code())
            .isAllowed()) {
      recordDenial(context, targetId);
      throw new Dhis2MappingException(Dhis2MappingException.Reason.NOT_AUTHORIZED);
    }
  }

  private void recordDenial(ExecutionContext context, UUID targetId) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.ofNullable(context).map(ExecutionContext::actor),
              Optional.ofNullable(context).map(ExecutionContext::scope),
              "ANALYTICS_DHIS2_MAPPING_DENIED",
              "Dhis2Mapping",
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
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private AuditEvidence mutationEvidence(
      ExecutionContext context, String action, UUID targetId, String reason) {
    return new AuditEvidence(
        AuditActionKind.PROTECTED_MUTATION,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        action,
        "Dhis2Mapping",
        Optional.of(new InternalId(targetId)),
        clock.instant(),
        AuditOutcome.SUCCEEDED,
        Optional.of(reason),
        authenticationEvidence(context),
        AuditAuthorizationOutcome.ALLOWED,
        context.correlationId());
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

  private static UUID auditId() {
    return UUID.randomUUID();
  }
}
