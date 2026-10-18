package org.ayuvahak.analytics.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion;
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
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Human-authorized, audited publication of immutable Analytics peer mappings. */
@Service
public final class FederatedAggregateMappingAdministrationService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final FederatedAggregateMappingStore mappings;
  private final AnalyticsStore analytics;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public FederatedAggregateMappingAdministrationService(
      FederatedAggregateMappingStore mappings,
      AnalyticsStore analytics,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.mappings = Objects.requireNonNull(mappings, "federated aggregate mappings");
    this.analytics = Objects.requireNonNull(analytics, "Analytics store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public FederatedAggregateMappingVersion publish(
      ExecutionContext context, FederatedAggregateMappingVersion proposed) {
    Objects.requireNonNull(proposed, "federated aggregate mapping");
    requireAdministrator(context, proposed, auditId());
    var definition =
        analytics
            .findDefinition(proposed.indicatorKey(), proposed.indicatorVersion())
            .filter(value -> value.fingerprint().equals(proposed.definitionFingerprint()))
            .orElseThrow(FederatedAggregateMappingConflictException::new);
    if (!definition.key().equals(proposed.indicatorKey())) {
      throw new FederatedAggregateMappingConflictException();
    }
    Instant now = clock.instant();
    try {
      return transactions.execute(
          status -> {
            FederatedAggregateMappingStore.PublishResult result = mappings.publish(proposed, now);
            if (!result.created()) {
              return result.mapping();
            }
            UUID targetId = auditId();
            InternalId auditRecordId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        "ANALYTICS_FEDERATED_AGGREGATE_MAPPING_PUBLISHED",
                        targetId,
                        proposed.direction().name() + "_VERSION_" + proposed.version()));
            mappings.attachAudit(proposed.mappingKey(), proposed.version(), auditRecordId.value());
            mappings.attachLifecycleAudit(proposed.mappingKey(), auditRecordId.value());
            return result.mapping();
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  public void deactivate(ExecutionContext context, String mappingKey) {
    int activeVersion =
        mappings
            .findActiveVersion(mappingKey)
            .orElseThrow(
                () ->
                    new FederatedAggregateException(
                        FederatedAggregateException.Reason.MAPPING_NOT_FOUND));
    FederatedAggregateMappingVersion current =
        mappings
            .findVersion(mappingKey, activeVersion)
            .orElseThrow(
                () ->
                    new FederatedAggregateException(
                        FederatedAggregateException.Reason.MAPPING_NOT_FOUND));
    UUID targetId = auditId();
    requireAdministrator(context, current, targetId);
    Instant now = clock.instant();
    try {
      transactions.execute(
          status -> {
            mappings.deactivate(mappingKey, now);
            InternalId auditRecordId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        "ANALYTICS_FEDERATED_AGGREGATE_MAPPING_DEACTIVATED",
                        targetId,
                        "INACTIVE"));
            mappings.attachLifecycleAudit(mappingKey, auditRecordId.value());
            return null;
          });
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private void requireAdministrator(
      ExecutionContext context, FederatedAggregateMappingVersion mapping, UUID targetId) {
    if (context == null
        || context.actor().kind() != ActorKind.HUMAN
        || !(context.sourceKind() == SourceKind.INTERACTIVE_REQUEST
            || context.sourceKind() == SourceKind.LOCAL_OPERATOR_COMMAND)
        || context.scope().kind() != ExecutionScope.Kind.ORGANIZATION
        || context
            .scope()
            .organization()
            .filter(mapping.administrativeOrganization()::equals)
            .isEmpty()
        || !DIRECTORY_ISSUER.equals(mapping.administrativeOrganization().issuer().value())
        || !authorization
            .evaluate(
                context, AnalyticsAuthorities.federatedAggregateMappingAdministration().code())
            .isAllowed()) {
      recordDenial(context, targetId);
      throw new FederatedAggregateException(FederatedAggregateException.Reason.NOT_AUTHORIZED);
    }
  }

  private void recordDenial(ExecutionContext context, UUID targetId) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.ofNullable(context).map(ExecutionContext::actor),
              Optional.ofNullable(context).map(ExecutionContext::scope),
              "ANALYTICS_FEDERATED_AGGREGATE_MAPPING_DENIED",
              "FederatedAggregateMapping",
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
      throw new FederatedAggregateException(
          FederatedAggregateException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private AuditEvidence mutationEvidence(
      ExecutionContext context, String action, UUID targetId, String reason) {
    return new AuditEvidence(
        AuditActionKind.PROTECTED_MUTATION,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        action,
        "FederatedAggregateMapping",
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
    return switch (context.authenticationMechanism()) {
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
