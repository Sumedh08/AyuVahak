package org.ayuvahak.platform.audit.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditTargetContext;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC adapter that writes only structured, minimized audit evidence. */
@Repository
@Lazy
public class JdbcAuditRecordAppender implements AuditRecordAppender {

  private static final String INSERT_SQL =
      "INSERT INTO audit.audit_record ("
          + "id, action_kind, actor_kind, actor_issuer, actor_reference_type, actor_reference, "
          + "scope_kind, organization_issuer, organization_reference_type, organization_reference, "
          + "facility_issuer, facility_reference_type, facility_reference, action_code, target_type, "
          + "target_id, occurred_at, recorded_at, outcome, reason_code, authentication_evidence, "
          + "authorization_outcome, correlation_id, target_scope_kind, "
          + "target_organization_issuer, target_organization_reference_type, "
          + "target_organization_reference, target_facility_issuer, "
          + "target_facility_reference_type, target_facility_reference) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
          + "?, ?, ?, ?, ?, ?)";

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate independentTransaction;

  public JdbcAuditRecordAppender(
      JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    independentTransaction = new TransactionTemplate(transactionManager);
    independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void appendInOwnerTransaction(InternalId id, AuditEvidence evidence, Instant recordedAt) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Mutation audit requires the protected operation's active transaction");
    }
    var connection = DataSourceUtils.getConnection(jdbcTemplate.getDataSource());
    try {
      if (!DataSourceUtils.isConnectionTransactional(connection, jdbcTemplate.getDataSource())) {
        throw new IllegalStateException(
            "Mutation audit must use the protected operation's transaction-bound database connection");
      }
    } finally {
      DataSourceUtils.releaseConnection(connection, jdbcTemplate.getDataSource());
    }
    insert(id, evidence, recordedAt);
  }

  @Override
  public void appendDurably(InternalId id, AuditEvidence evidence, Instant recordedAt) {
    try {
      independentTransaction.executeWithoutResult(status -> insert(id, evidence, recordedAt));
    } catch (AuditPersistenceUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new AuditPersistenceUnavailableException(failure);
    }
  }

  private void insert(InternalId id, AuditEvidence evidence, Instant recordedAt) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(evidence, "evidence");
    Objects.requireNonNull(recordedAt, "recordedAt");

    ActorReference actor = evidence.actor().orElse(null);
    ExternalReference actorIdentity = actor == null ? null : actor.identity();
    ExecutionScope scope = evidence.scope().orElse(null);
    ExternalReference organization = scope == null ? null : scope.organization().orElse(null);
    ExternalReference facility = scope == null ? null : scope.facility().orElse(null);
    AuditTargetContext targetContext = evidence.targetContext().orElse(null);
    ExternalReference targetOrganization =
        targetContext == null ? null : targetContext.organization().orElse(null);
    ExternalReference targetFacility =
        targetContext == null ? null : targetContext.facility().orElse(null);

    try {
      jdbcTemplate.update(
          INSERT_SQL,
          id.value(),
          evidence.actionKind().name(),
          actor == null ? null : actor.kind().name(),
          actorIdentity == null ? null : actorIdentity.issuer().value(),
          actorIdentity == null ? null : actorIdentity.resourceType(),
          actorIdentity == null ? null : actorIdentity.value(),
          scope == null ? null : scope.kind().name(),
          organization == null ? null : organization.issuer().value(),
          organization == null ? null : organization.resourceType(),
          organization == null ? null : organization.value(),
          facility == null ? null : facility.issuer().value(),
          facility == null ? null : facility.resourceType(),
          facility == null ? null : facility.value(),
          evidence.actionCode(),
          evidence.targetType(),
          evidence.targetId().map(InternalId::value).orElse(null),
          Timestamp.from(evidence.occurredAt()),
          Timestamp.from(recordedAt),
          evidence.outcome().name(),
          evidence.reasonCode().orElse(null),
          evidence.authenticationEvidence().name(),
          evidence.authorizationOutcome().name(),
          evidence.correlationId().value(),
          targetContext == null ? null : targetContext.kind().name(),
          targetOrganization == null ? null : targetOrganization.issuer().value(),
          targetOrganization == null ? null : targetOrganization.resourceType(),
          targetOrganization == null ? null : targetOrganization.value(),
          targetFacility == null ? null : targetFacility.issuer().value(),
          targetFacility == null ? null : targetFacility.resourceType(),
          targetFacility == null ? null : targetFacility.value());
    } catch (RuntimeException failure) {
      throw new AuditPersistenceUnavailableException(failure);
    }
  }
}
