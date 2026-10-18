package org.ayuvahak.analytics.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.Dhis2ExportOperation;
import org.ayuvahak.analytics.domain.Dhis2ExportReconciliation;
import org.ayuvahak.analytics.domain.Dhis2ExportState;
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

/** Authorized exact-tuple operator resolution for ambiguous one-value DHIS2 exports. */
@Service
public final class Dhis2ExportReconciliationService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final AnalyticsStore snapshots;
  private final Dhis2ExportOperationStore operations;
  private final AnalyticsDisclosureReservationService disclosure;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public Dhis2ExportReconciliationService(
      AnalyticsStore snapshots,
      Dhis2ExportOperationStore operations,
      AnalyticsDisclosureReservationService disclosure,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.snapshots = Objects.requireNonNull(snapshots, "Analytics snapshot store");
    this.operations = Objects.requireNonNull(operations, "DHIS2 operation store");
    this.disclosure = Objects.requireNonNull(disclosure, "disclosure service");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public Dhis2ExportOperation reconcile(
      ExecutionContext context, Dhis2ExportReconciliationRequest request) {
    Objects.requireNonNull(request, "reconciliation request");
    Dhis2ExportOperation operation =
        operations
            .find(request.operationId())
            .orElseThrow(
                () ->
                    new Dhis2ExportException(Dhis2ExportException.Reason.RECONCILIATION_REQUIRED));
    AggregateSnapshot snapshot =
        snapshots
            .findSnapshotById(operation.snapshotId())
            .orElseThrow(
                () -> new Dhis2ExportException(Dhis2ExportException.Reason.SNAPSHOT_NOT_FOUND));
    authorize(context, request, operation, snapshot);
    if (request.resolution() == Dhis2ExportReconciliation.Resolution.CONFIRMED_APPLIED
        && (snapshot.value().isEmpty()
            || request.observedValue().orElseThrow() != snapshot.value().orElseThrow())) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.RECONCILIATION_REQUIRED);
    }

    try {
      return transactions.execute(
          status -> {
            Dhis2ExportOperation current =
                operations
                    .find(request.operationId())
                    .orElseThrow(Dhis2ExportOperationConflictException::new);
            if (!current.targetTuple().equals(request.targetTuple())
                || (current.state() != Dhis2ExportState.OUTCOME_UNKNOWN
                    && current.state() != Dhis2ExportState.RECONCILIATION_REQUIRED)
                || !operations.hasTargetTupleClaim(current.operationId())
                || current.disclosureReservationId().isEmpty()) {
              throw new Dhis2ExportOperationConflictException();
            }
            Instant resolvedAt = clock.instant();
            boolean applied =
                request.resolution() == Dhis2ExportReconciliation.Resolution.CONFIRMED_APPLIED;
            InternalId auditId =
                audit.recordMutation(
                    mutationEvidence(
                        context,
                        applied
                            ? "ANALYTICS_DHIS2_EXPORT_OPERATOR_CONFIRMED_APPLIED"
                            : "ANALYTICS_DHIS2_EXPORT_OPERATOR_CONFIRMED_QUIESCED",
                        current.operationId(),
                        request.evidenceReference()));
            disclosure.resolveDhis2DisclosureByOperator(
                context, current.disclosureReservationId().orElseThrow(), request.resolution());
            Dhis2ExportState targetState =
                applied ? Dhis2ExportState.ACKNOWLEDGED : Dhis2ExportState.REJECTED;
            Dhis2ExportOperation resolved =
                operations.update(
                    current.operationId(),
                    current.state(),
                    targetState,
                    applied
                        ? "OPERATOR_CONFIRMED_APPLIED"
                        : "OPERATOR_CONFIRMED_NOT_APPLIED_QUIESCED",
                    current.attemptCount(),
                    Optional.empty(),
                    Optional.empty(),
                    current.disclosureReservationId(),
                    resolvedAt,
                    auditId.value());
            operations.recordReconciliation(
                new Dhis2ExportReconciliation(
                    current.operationId(),
                    request.resolution(),
                    request.evidenceReference(),
                    request.observedValue(),
                    auditId.value(),
                    resolvedAt));
            return resolved;
          });
    } catch (Dhis2ExportOperationConflictException conflict) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.RECONCILIATION_REQUIRED, conflict);
    } catch (DataAccessException | AuditPersistenceUnavailableException unavailable) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private void authorize(
      ExecutionContext context,
      Dhis2ExportReconciliationRequest request,
      Dhis2ExportOperation operation,
      AggregateSnapshot snapshot) {
    if (context == null
        || context.actor().kind() != ActorKind.HUMAN
        || !(context.sourceKind() == SourceKind.INTERACTIVE_REQUEST
            || context.sourceKind() == SourceKind.LOCAL_OPERATOR_COMMAND)
        || context.scope().kind() != ExecutionScope.Kind.ORGANIZATION
        || context.scope().organization().filter(snapshot.organization()::equals).isEmpty()
        || !DIRECTORY_ISSUER.equals(snapshot.organization().issuer().value())
        || !operation.targetTuple().equals(request.targetTuple())
        || !authorization
            .evaluate(context, AnalyticsAuthorities.dhis2ExportReconciliation().code())
            .isAllowed()) {
      recordDenial(context, request.operationId());
      throw new Dhis2ExportException(Dhis2ExportException.Reason.NOT_AUTHORIZED);
    }
  }

  private void recordDenial(ExecutionContext context, UUID operationId) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.ofNullable(context).map(ExecutionContext::actor),
              Optional.ofNullable(context).map(ExecutionContext::scope),
              "ANALYTICS_DHIS2_RECONCILIATION_DENIED",
              "Dhis2ExportOperation",
              Optional.of(new InternalId(operationId)),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of("AUTHORIZATION_OR_TARGET_DENIED"),
              authenticationEvidence(context),
              context == null
                  ? AuditAuthorizationOutcome.NOT_EVALUATED
                  : AuditAuthorizationOutcome.DENIED,
              context == null
                  ? new CorrelationId(operationId.toString())
                  : context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new Dhis2ExportException(Dhis2ExportException.Reason.STORE_UNAVAILABLE, unavailable);
    }
  }

  private AuditEvidence mutationEvidence(
      ExecutionContext context, String action, UUID operationId, UUID evidenceReference) {
    return new AuditEvidence(
        AuditActionKind.PROTECTED_MUTATION,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        action,
        "Dhis2ExportOperation",
        Optional.of(new InternalId(operationId)),
        clock.instant(),
        AuditOutcome.SUCCEEDED,
        Optional.of("EVIDENCE_REF_" + evidenceReference.toString().replace("-", "").toUpperCase()),
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
}
