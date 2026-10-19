package org.ayuvahak.referral.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.retention.RetentionReapplicationProperties;
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-authorized holds and identifier restrictions for closed Referral records. */
@Service
@Lazy
public final class ReferralRetentionOperations {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final ReferralDraftStore drafts;
  private final TransferAmendmentStatusPort transferStatus;
  private final RetentionReapplicationProperties reapplicationProperties;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public ReferralRetentionOperations(
      @Lazy ReferralDraftStore drafts,
      TransferAmendmentStatusPort transferStatus,
      RetentionReapplicationProperties reapplicationProperties,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.drafts = Objects.requireNonNull(drafts, "Referral store");
    this.transferStatus = Objects.requireNonNull(transferStatus, "Transfer status port");
    this.reapplicationProperties =
        Objects.requireNonNull(reapplicationProperties, "retention reapplication properties");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
  }

  public RetentionHoldResult placeHold(
      ExecutionContext context,
      org.ayuvahak.platform.core.InternalId referralId,
      ReferralRetentionHoldPurpose purpose,
      Instant reviewBy) {
    Objects.requireNonNull(purpose, "hold purpose");
    Objects.requireNonNull(reviewBy, "hold review time");
    requireOriginScope(context);
    requireRetentionAuthority(context, "REFERRAL_RETENTION_HOLD");
    Instant now = clock.instant();
    if (!reviewBy.isAfter(now)) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "A Referral retention hold review time must be in the future.");
    }
    UUID holdId = UUID.randomUUID();
    try {
      return transaction.execute(
          status -> {
            ReferralDraft draft = requireOwnedDraft(context.scope(), referralId, true);
            if (drafts.isIdentifierRestricted(referralId)) {
              throw conflict("The Referral identifier is already restricted.");
            }
            if (!drafts.createHold(
                holdId, referralId, purpose, reviewBy, context.actor(), now)) {
              throw referralUnavailable();
            }
            auditMutation(context, draft, "REFERRAL_RETENTION_HOLD", purpose.name());
            return new RetentionHoldResult(holdId, referralId, purpose, reviewBy);
          });
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    } catch (DataAccessException unavailable) {
      throw referralUnavailable();
    }
  }

  public void releaseHold(
      ExecutionContext context,
      org.ayuvahak.platform.core.InternalId referralId,
      UUID holdId,
      ReferralRetentionHoldReleaseReason reason) {
    Objects.requireNonNull(holdId, "hold id");
    Objects.requireNonNull(reason, "hold release reason");
    requireOriginScope(context);
    requireRetentionAuthority(context, "REFERRAL_RETENTION_HOLD_RELEASE");
    try {
      transaction.executeWithoutResult(
          status -> {
            ReferralDraft draft = requireOwnedDraft(context.scope(), referralId, true);
            if (!drafts.releaseHold(holdId, referralId, reason, context.actor(), clock.instant())) {
              throw problem(
                  HttpStatus.NOT_FOUND,
                  PlatformErrorCode.REFERRAL_NOT_FOUND,
                  "The active Referral retention hold was not found.");
            }
            auditMutation(context, draft, "REFERRAL_RETENTION_HOLD_RELEASE", reason.name());
          });
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    } catch (DataAccessException unavailable) {
      throw referralUnavailable();
    }
  }

  public IdentifierRestrictionResult restrictIdentifier(
      ExecutionContext context,
      org.ayuvahak.platform.core.InternalId referralId,
      ReferralIdentifierRestrictionReason reason) {
    Objects.requireNonNull(reason, "restriction reason");
    requireOriginScope(context);
    requireRetentionAuthority(context, "REFERRAL_IDENTIFIER_RESTRICTION");
    try {
      return transaction.execute(
          status -> {
            ReferralDraft draft = requireOwnedDraft(context.scope(), referralId, true);
            if (draft.state() != ReferralState.CANCELLED && draft.state() != ReferralState.RESOLVED) {
              throw conflict("Only a cancelled or resolved Referral may have its identifier restricted.");
            }
            if (drafts.hasActiveHold(referralId)) {
              throw conflict("An active retention hold prevents Referral identifier restriction.");
            }
            final boolean activeCoordination;
            try {
              activeCoordination = transferStatus.hasActiveCoordination(context, referralId);
            } catch (TransferAmendmentStatusUnavailableException unavailable) {
              throw problem(
                  HttpStatus.SERVICE_UNAVAILABLE,
                  PlatformErrorCode.REFERRAL_UNAVAILABLE,
                  "Transfer could not confirm that Referral coordination is closed safely.");
            }
            if (activeCoordination) {
              throw conflict("Active Transfer coordination prevents Referral identifier restriction.");
            }
            UUID restrictionId = UUID.randomUUID();
            boolean created =
                drafts.restrictIdentifier(
                    restrictionId, referralId, reason, context.actor(), clock.instant());
            if (created) {
              auditMutation(context, draft, "REFERRAL_IDENTIFIER_RESTRICTION", reason.name());
            }
            return new IdentifierRestrictionResult(referralId, created, true);
          });
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    } catch (DataAccessException unavailable) {
      throw referralUnavailable();
    }
  }

  /** Used only to omit the opaque identifier from an already-authorized owner response. */
  public boolean isIdentifierRestricted(org.ayuvahak.platform.core.InternalId referralId) {
    try {
      return drafts.isIdentifierRestricted(referralId);
    } catch (DataAccessException unavailable) {
      throw referralUnavailable();
    }
  }

  public ReferralRetentionManifest exportManifest(
      ExecutionContext context, UUID restoreId, Instant backupCompletedAt) {
    Objects.requireNonNull(restoreId, "restore id");
    Objects.requireNonNull(backupCompletedAt, "backup completion time");
    requireReapplicationAuthority(context, "REFERRAL_RETENTION_MANIFEST_EXPORT");
    Instant generatedAt = clock.instant();
    if (!generatedAt.isAfter(backupCompletedAt)) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "Backup completion must precede retention manifest generation.");
    }
    try {
      ReferralRetentionManifest manifest =
          drafts.exportSince(restoreId, backupCompletedAt, generatedAt);
      recordManifestRead(context);
      return manifest;
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    } catch (DataAccessException unavailable) {
      throw referralUnavailable();
    }
  }

  public void reapplyManifest(ExecutionContext context, ReferralRetentionManifest manifest) {
    Objects.requireNonNull(manifest, "retention manifest");
    requireReapplicationAuthority(context, "REFERRAL_RETENTION_MANIFEST_REAPPLY");
    final UUID requiredRestoreId;
    try {
      requiredRestoreId =
          reapplicationProperties
              .requiredRestoreId()
              .orElseThrow(
                  () ->
                      problem(
                          HttpStatus.CONFLICT,
                          PlatformErrorCode.VALIDATION_FAILED,
                          "A required restore id must be configured before reapplication."));
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.VALIDATION_FAILED,
          "The configured restore id is invalid.");
    }
    if (!requiredRestoreId.equals(manifest.restoreId())) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.VALIDATION_FAILED,
          "The retention manifest does not match the configured restore id.");
    }
    try {
      transaction.executeWithoutResult(
          status -> {
            if (drafts.hasCompleted(manifest.restoreId())) return;
            drafts.reapply(manifest, clock.instant());
            recordManifestMutation(context);
          });
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    } catch (DataAccessException unavailable) {
      throw referralUnavailable();
    } catch (IllegalStateException conflict) {
      throw problem(
          HttpStatus.CONFLICT,
          PlatformErrorCode.VALIDATION_FAILED,
          "The retention manifest conflicts with restored Referral state.");
    }
  }

  private void requireReapplicationAuthority(ExecutionContext context, String action) {
    Objects.requireNonNull(context, "execution context");
    if (context.scope().kind() != ExecutionScope.Kind.PLATFORM) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Referral retention reapplication requires explicit platform operator scope.");
    }
    AuthorizationDecision decision =
        authorization
            .getObject()
            .evaluate(context, ReferralAuthorities.retentionReapplication().code());
    if (decision.isAllowed()) return;
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  action,
                  "ReferralRetentionManifest",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of("AUTHORIZATION_DENIED"),
                  authentication(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    }
    throw problem(
        decision.denialReason().orElse(null)
                == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
            ? HttpStatus.SERVICE_UNAVAILABLE
            : HttpStatus.FORBIDDEN,
        decision.denialReason().orElse(null)
                == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated actor is not authorized for Referral retention reapplication.");
  }

  private void recordManifestRead(ExecutionContext context) {
    auditWriter
        .getObject()
        .recordNonMutating(
            new AuditEvidence(
                AuditActionKind.SENSITIVE_READ,
                Optional.of(context.actor()),
                Optional.of(context.scope()),
                "REFERRAL_RETENTION_MANIFEST_EXPORT",
                "ReferralRetentionManifest",
                Optional.empty(),
                clock.instant(),
                AuditOutcome.SUCCEEDED,
                Optional.empty(),
                authentication(context.authenticationMechanism()),
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId()));
  }

  private void recordManifestMutation(ExecutionContext context) {
    auditWriter
        .getObject()
        .recordMutation(
            new AuditEvidence(
                AuditActionKind.PROTECTED_MUTATION,
                Optional.of(context.actor()),
                Optional.of(context.scope()),
                "REFERRAL_RETENTION_MANIFEST_REAPPLY",
                "ReferralRetentionManifest",
                Optional.empty(),
                clock.instant(),
                AuditOutcome.SUCCEEDED,
                Optional.empty(),
                authentication(context.authenticationMechanism()),
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId()));
  }

  private ReferralDraft requireOwnedDraft(
      ExecutionScope scope, org.ayuvahak.platform.core.InternalId referralId, boolean lock) {
    Optional<ReferralDraft> found =
        lock ? drafts.lockDraft(referralId) : drafts.findDraft(referralId);
    ReferralDraft draft =
        found.orElseThrow(
            () ->
                problem(
                    HttpStatus.NOT_FOUND,
                    PlatformErrorCode.REFERRAL_NOT_FOUND,
                    "The requested Referral was not found."));
    if (!scope.organization().equals(Optional.of(draft.originOrganization()))
        || !scope.facility().equals(draft.originFacility())) {
      throw problem(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.REFERRAL_NOT_FOUND,
          "The requested Referral was not found.");
    }
    return draft;
  }

  private void requireRetentionAuthority(ExecutionContext context, String action) {
    AuthorizationDecision decision =
        authorization.getObject().evaluate(context, ReferralAuthorities.retention().code());
    if (decision.isAllowed()) return;
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  action,
                  "Referral",
                  Optional.empty(),
                  clock.instant(),
                  AuditOutcome.DENIED,
                  Optional.of("AUTHORIZATION_DENIED"),
                  authentication(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.DENIED,
                  context.correlationId(),
                  Optional.of(target(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw auditUnavailable();
    }
    boolean unavailable =
        decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE;
    throw problem(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
        unavailable
            ? PlatformErrorCode.AUTHORIZATION_UNAVAILABLE
            : PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated actor is not authorized for this Referral retention operation.");
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
          "Referral retention requires an explicit Directory origin scope.");
    }
  }

  private void auditMutation(
      ExecutionContext context, ReferralDraft draft, String action, String reasonCode) {
    auditWriter
        .getObject()
        .recordMutation(
            new AuditEvidence(
                AuditActionKind.PROTECTED_MUTATION,
                Optional.of(context.actor()),
                Optional.of(context.scope()),
                action,
                "Referral",
                Optional.of(draft.id()),
                clock.instant(),
                AuditOutcome.SUCCEEDED,
                Optional.of(reasonCode),
                authentication(context.authenticationMechanism()),
                AuditAuthorizationOutcome.ALLOWED,
                context.correlationId(),
                Optional.of(target(context.scope()))));
  }

  private static AuditTargetContext target(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
  }

  private static AuditAuthenticationEvidence authentication(AuthenticationMechanism mechanism) {
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static PlatformProblemException conflict(String detail) {
    return problem(HttpStatus.CONFLICT, PlatformErrorCode.REFERRAL_STATE_CONFLICT, detail);
  }

  private static PlatformProblemException auditUnavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.AUDIT_UNAVAILABLE,
        "Referral retention state was not changed because its audit record is unavailable.");
  }

  private static PlatformProblemException referralUnavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.REFERRAL_UNAVAILABLE,
        "Referral retention state could not be read or changed safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  public record RetentionHoldResult(
      UUID holdId,
      org.ayuvahak.platform.core.InternalId referralId,
      ReferralRetentionHoldPurpose purpose,
      Instant reviewBy) {}

  public record IdentifierRestrictionResult(
      org.ayuvahak.platform.core.InternalId referralId,
      boolean created,
      boolean patientReferenceAccessRestricted) {}
}
