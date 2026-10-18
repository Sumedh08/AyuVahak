package org.ayuvahak.analytics.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AggregateSnapshotFinalization;
import org.ayuvahak.analytics.domain.AggregateSnapshotRevision;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
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
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.SourceKind;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorized Analytics revision/finalization commands over immutable computed snapshots. */
@Service
public final class AnalyticsSnapshotRevisionService {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final Duration MAX_CORRECTION_WINDOW = Duration.ofDays(365);

  private final AnalyticsStore snapshots;
  private final AnalyticsRevisionStore revisions;
  private final AuthorizationService authorization;
  private final AuditWriter audit;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Duration correctionWindow;

  public AnalyticsSnapshotRevisionService(
      AnalyticsStore snapshots,
      AnalyticsRevisionStore revisions,
      AuthorizationService authorization,
      AuditWriter audit,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock,
      @Value("${ayuvahak.analytics.correction-window:PT720H}") Duration correctionWindow) {
    this.snapshots = Objects.requireNonNull(snapshots, "Analytics snapshot store");
    this.revisions = Objects.requireNonNull(revisions, "Analytics revision store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.audit = Objects.requireNonNull(audit, "audit writer");
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = Objects.requireNonNull(clock, "audit clock");
    this.correctionWindow = Objects.requireNonNull(correctionWindow, "correction window");
    if (correctionWindow.isZero()
        || correctionWindow.isNegative()
        || correctionWindow.compareTo(MAX_CORRECTION_WINDOW) > 0) {
      throw new IllegalArgumentException(
          "Analytics correction window must be positive and no longer than 365 days");
    }
  }

  public AggregateSnapshotRevision registerInitial(
      ExecutionContext context, RegisterAggregateSnapshotRequest request) {
    Objects.requireNonNull(request, "registration request");
    AggregateSnapshot snapshot = requireSnapshot(request.snapshotId());
    requireOrganizationScope(
        context, snapshot, request.operationId(), "ANALYTICS_SNAPSHOT_REGISTRATION_DENIED");
    requireAnalyticsWorker(context, request.operationId());
    requireAuthorized(
        context,
        AnalyticsAuthorities.aggregateComputation().code(),
        "ANALYTICS_SNAPSHOT_REGISTRATION_DENIED",
        request.operationId());

    Instant recordedAt = clock.instant();
    AggregateSnapshotRevision result;
    try {
      result =
          transactions.execute(
              status -> {
                AggregateSnapshotRevision registered =
                    revisions.registerInitial(
                        snapshot,
                        request.operationId(),
                        recordedAt,
                        recordedAt.plus(correctionWindow));
                if (registered.replayed()) {
                  return registered;
                }
                InternalId auditId =
                    audit.recordMutation(
                        mutationEvidence(
                            context,
                            "ANALYTICS_SNAPSHOT_REGISTERED",
                            snapshot.snapshotId(),
                            "INITIAL"));
                revisions.attachRevisionAudit(request.operationId(), auditId.value());
                return withAudit(registered, auditId.value());
              });
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new AnalyticsSnapshotRevisionException(
          AnalyticsSnapshotRevisionException.Reason.AUDIT_UNAVAILABLE, unavailable);
    }
    return Objects.requireNonNull(result, "revision registration result");
  }

  public AggregateSnapshotRevision appendRevision(
      ExecutionContext context, AppendAggregateSnapshotRevisionRequest request) {
    Objects.requireNonNull(request, "revision request");
    AggregateSnapshot previous = requireSnapshot(request.expectedSnapshotId());
    AggregateSnapshot replacement = requireSnapshot(request.replacementSnapshotId());
    requireOrganizationScope(
        context, previous, request.operationId(), "ANALYTICS_SNAPSHOT_REVISION_DENIED");
    requireOrganizationScope(
        context, replacement, request.operationId(), "ANALYTICS_SNAPSHOT_REVISION_DENIED");

    boolean lateAuthorized =
        authorization
            .evaluate(context, AnalyticsAuthorities.lateAggregateRevision().code())
            .isAllowed();
    boolean correctionAuthorized =
        authorization
            .evaluate(context, AnalyticsAuthorities.aggregateSnapshotCorrection().code())
            .isAllowed();
    if (!lateAuthorized && !correctionAuthorized) {
      recordDenial(context, request.operationId(), "ANALYTICS_SNAPSHOT_REVISION_DENIED");
      throw new AnalyticsSnapshotRevisionException(
          AnalyticsSnapshotRevisionException.Reason.NOT_AUTHORIZED);
    }

    Instant recordedAt = clock.instant();
    AggregateSnapshotRevision result;
    try {
      result =
          transactions.execute(
              status -> {
                AggregateSnapshotRevision appended =
                    revisions.appendRevision(
                        previous,
                        replacement,
                        request,
                        recordedAt,
                        lateAuthorized,
                        correctionAuthorized);
                if (appended.replayed()) {
                  return appended;
                }
                String actionCode =
                    appended.authorizedCorrection()
                        ? "ANALYTICS_SNAPSHOT_CORRECTED"
                        : "ANALYTICS_SNAPSHOT_REVISED";
                InternalId auditId =
                    audit.recordMutation(
                        mutationEvidence(
                            context, actionCode, replacement.snapshotId(), request.cause().name()));
                revisions.attachRevisionAudit(request.operationId(), auditId.value());
                return withAudit(appended, auditId.value());
              });
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new AnalyticsSnapshotRevisionException(
          AnalyticsSnapshotRevisionException.Reason.AUDIT_UNAVAILABLE, unavailable);
    } catch (AnalyticsSnapshotRevisionException failure) {
      if (failure.reason() == AnalyticsSnapshotRevisionException.Reason.NOT_AUTHORIZED
          || failure.reason()
              == AnalyticsSnapshotRevisionException.Reason.FINALIZED_CORRECTION_REQUIRED) {
        recordDenial(context, request.operationId(), "ANALYTICS_SNAPSHOT_CORRECTION_DENIED");
      }
      throw failure;
    }
    return Objects.requireNonNull(result, "revision result");
  }

  public AggregateSnapshotFinalization finalizeSnapshot(
      ExecutionContext context, FinalizeAggregateSnapshotRequest request) {
    Objects.requireNonNull(request, "finalization request");
    AggregateSnapshot snapshot = requireSnapshot(request.snapshotId());
    requireOrganizationScope(
        context, snapshot, request.operationId(), "ANALYTICS_SNAPSHOT_FINALIZATION_DENIED");
    requireAuthorized(
        context,
        AnalyticsAuthorities.aggregateSnapshotFinalization().code(),
        "ANALYTICS_SNAPSHOT_FINALIZATION_DENIED",
        request.operationId());

    Instant finalizedAt = clock.instant();
    AggregateSnapshotFinalization result;
    try {
      result =
          transactions.execute(
              status -> {
                AggregateSnapshotFinalization finalized =
                    revisions.finalizeSnapshot(snapshot, request, finalizedAt);
                if (finalized.replayed()) {
                  return finalized;
                }
                InternalId auditId =
                    audit.recordMutation(
                        mutationEvidence(
                            context,
                            "ANALYTICS_SNAPSHOT_FINALIZED",
                            snapshot.snapshotId(),
                            request.reason().name()));
                revisions.attachFinalizationAudit(request.operationId(), auditId.value());
                return withAudit(finalized, auditId.value());
              });
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new AnalyticsSnapshotRevisionException(
          AnalyticsSnapshotRevisionException.Reason.AUDIT_UNAVAILABLE, unavailable);
    }
    return Objects.requireNonNull(result, "finalization result");
  }

  public Optional<AggregateSnapshotRevision> currentRevision(
      ExecutionContext context, UUID anySnapshotId) {
    AggregateSnapshot snapshot = requireSnapshot(anySnapshotId);
    requireOrganizationScope(
        context, snapshot, anySnapshotId, "ANALYTICS_SNAPSHOT_REVISION_READ_DENIED");
    requireAuthorized(
        context,
        AnalyticsAuthorities.aggregateComputation().code(),
        "ANALYTICS_SNAPSHOT_REVISION_READ_DENIED",
        anySnapshotId);
    return revisions.currentRevision(anySnapshotId);
  }

  private AggregateSnapshot requireSnapshot(UUID snapshotId) {
    return snapshots
        .findSnapshotById(snapshotId)
        .orElseThrow(
            () ->
                new AnalyticsSnapshotRevisionException(
                    AnalyticsSnapshotRevisionException.Reason.SNAPSHOT_NOT_FOUND));
  }

  private void requireOrganizationScope(
      ExecutionContext context, AggregateSnapshot snapshot, UUID operationId, String deniedAction) {
    if (context == null
        || context.scope().kind() != ExecutionScope.Kind.ORGANIZATION
        || context.scope().organization().filter(snapshot.organization()::equals).isEmpty()
        || !DIRECTORY_ISSUER.equals(snapshot.organization().issuer().value())
        || !"Organization".equals(snapshot.organization().resourceType())) {
      recordDenial(context, operationId, deniedAction);
      throw new AnalyticsSnapshotRevisionException(
          AnalyticsSnapshotRevisionException.Reason.NOT_AUTHORIZED);
    }
  }

  private void requireAnalyticsWorker(ExecutionContext context, UUID operationId) {
    if (context == null
        || context.actor().kind() != ActorKind.SERVICE
        || !(context.sourceKind() == SourceKind.INTEGRATION
            || context.sourceKind() == SourceKind.BACKGROUND_WORK
            || context.sourceKind() == SourceKind.SCHEDULED_WORK)) {
      recordDenial(context, operationId, "ANALYTICS_SNAPSHOT_REGISTRATION_DENIED");
      throw new AnalyticsSnapshotRevisionException(
          AnalyticsSnapshotRevisionException.Reason.NOT_AUTHORIZED);
    }
  }

  private void requireAuthorized(
      ExecutionContext context, String authority, String deniedAction, UUID operationId) {
    AuthorizationDecision decision = authorization.evaluate(context, authority);
    if (decision.isAllowed()) {
      return;
    }
    recordDenial(context, operationId, deniedAction);
    throw new AnalyticsSnapshotRevisionException(
        AnalyticsSnapshotRevisionException.Reason.NOT_AUTHORIZED);
  }

  private void recordDenial(ExecutionContext context, UUID targetId, String action) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.ofNullable(context).map(ExecutionContext::actor),
              Optional.ofNullable(context).map(ExecutionContext::scope),
              action,
              "AggregateSnapshot",
              Optional.of(new InternalId(targetId)),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of("AUTHORIZATION_OR_STATE_DENIED"),
              authenticationEvidence(context),
              context == null
                  ? AuditAuthorizationOutcome.NOT_EVALUATED
                  : AuditAuthorizationOutcome.DENIED,
              context == null
                  ? new org.ayuvahak.platform.core.CorrelationId(targetId.toString())
                  : context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new AnalyticsSnapshotRevisionException(
          AnalyticsSnapshotRevisionException.Reason.AUDIT_UNAVAILABLE, unavailable);
    }
  }

  private AuditEvidence mutationEvidence(
      ExecutionContext context, String actionCode, UUID snapshotId, String reasonCode) {
    return new AuditEvidence(
        AuditActionKind.PROTECTED_MUTATION,
        Optional.of(context.actor()),
        Optional.of(context.scope()),
        actionCode,
        "AggregateSnapshot",
        Optional.of(new InternalId(snapshotId)),
        clock.instant(),
        AuditOutcome.SUCCEEDED,
        Optional.of(reasonCode),
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

  private static AggregateSnapshotRevision withAudit(
      AggregateSnapshotRevision revision, UUID auditRecordId) {
    return new AggregateSnapshotRevision(
        revision.seriesId(),
        revision.revisionNumber(),
        revision.snapshotId(),
        revision.previousSnapshotId(),
        revision.cause(),
        revision.state(),
        revision.latestSourceCutoff(),
        revision.correctionWindowEndsAt(),
        revision.recordedAt(),
        Optional.of(auditRecordId),
        revision.authorizedCorrection(),
        revision.replayed());
  }

  private static AggregateSnapshotFinalization withAudit(
      AggregateSnapshotFinalization finalization, UUID auditRecordId) {
    return new AggregateSnapshotFinalization(
        finalization.operationId(),
        finalization.seriesId(),
        finalization.snapshotId(),
        finalization.revisionNumber(),
        finalization.reason(),
        finalization.finalizedAt(),
        Optional.of(auditRecordId),
        finalization.replayed());
  }
}
