package org.ayuvahak.referral.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.application.DirectoryReferralReferenceLookup;
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
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.event.AggregatePosition;
import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;
import org.ayuvahak.platform.eventing.application.OutboxWriter;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.referral.domain.CareRequirement;
import org.ayuvahak.referral.domain.PatientReference;
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.ayuvahak.referral.domain.ReferralCancellation;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralResolution;
import org.ayuvahak.referral.domain.ReferralResolutionOutcome;
import org.ayuvahak.referral.domain.ReferralState;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Referral owner commands for origin-scoped coordination state. */
@Service
@Lazy
public class ReferralDraftCommands {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String COMMAND_BUSY_DETAIL =
      "A matching Referral command is still being processed; retry with the same idempotency key.";

  private final ReferralDraftStore drafts;
  private final DirectoryReferralReferenceLookup directoryReferences;
  private final TransferAmendmentStatusPort transferAmendmentStatus;
  private final ObjectProvider<AuthorizationService> authorization;
  private final ObjectProvider<AuditWriter> auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final OutboxWriter outboxWriter;
  private final ObjectMapper objectMapper;

  public ReferralDraftCommands(
      @Lazy ReferralDraftStore drafts,
      DirectoryReferralReferenceLookup directoryReferences,
      TransferAmendmentStatusPort transferAmendmentStatus,
      ObjectProvider<AuthorizationService> authorization,
      ObjectProvider<AuditWriter> auditWriter,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock,
      OutboxWriter outboxWriter,
      ObjectMapper objectMapper) {
    this.drafts = Objects.requireNonNull(drafts, "Referral draft store");
    this.directoryReferences =
        Objects.requireNonNull(directoryReferences, "Directory reference contract");
    this.transferAmendmentStatus =
        Objects.requireNonNull(transferAmendmentStatus, "Transfer amendment-status contract");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "audit clock");
    this.outboxWriter = Objects.requireNonNull(outboxWriter, "outbox writer");
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper");
  }

  public ReferralDraftCommandResult create(
      ExecutionContext context,
      PatientReference patientReference,
      CareRequirement careRequirement,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(patientReference, "patient reference");
    Objects.requireNonNull(careRequirement, "care requirement");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireOriginScope(context);
    requireAuthority(context, ReferralAuthorities.create(), "REFERRAL_DRAFT_CREATE");
    CommandReceiptKey receiptKey =
        commandKey(context, "referral.draft.create", "referrals", idempotencyKey);
    return execute(
        () -> {
          Instant now = clock.instant();
          drafts.configureBoundedReceiptWait();
          ReferralDraftStore.ReceiptClaim claim = drafts.claimReceipt(receiptKey, fingerprint, now);
          ReferralDraftCommandResult replay = replay(claim);
          if (replay != null) {
            return replay;
          }
          requireValidDirectoryReferences(context.scope(), careRequirement);
          InternalId id = InternalId.generate();
          ReferralDraft draft =
              new ReferralDraft(
                  id,
                  context.scope().organization().orElseThrow(),
                  context.scope().facility(),
                  patientReference,
                  careRequirement,
                  context.actor(),
                  context.actor(),
                  ReferralState.DRAFT,
                  1,
                  now,
                  now);
          drafts.insertDraft(draft);
          auditMutation(context, draft, "REFERRAL_DRAFT_CREATE");
          drafts.completeReceipt(receiptKey, HttpStatus.CREATED.value(), id, draft.version(), now);
          return new ReferralDraftCommandResult(draft, false, HttpStatus.CREATED.value());
        });
  }

  public ReferralDraftCommandResult update(
      ExecutionContext context,
      InternalId referralId,
      long expectedVersion,
      PatientReference patientReference,
      CareRequirement careRequirement,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(referralId, "referral id");
    Objects.requireNonNull(patientReference, "patient reference");
    Objects.requireNonNull(careRequirement, "care requirement");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireOriginScope(context);
    if (expectedVersion < 1) {
      throw requirementInvalid("A positive expected draft version is required.");
    }
    requireAuthority(context, ReferralAuthorities.edit(), "REFERRAL_DRAFT_EDIT");
    CommandReceiptKey receiptKey =
        commandKey(context, "referral.draft.edit", "referrals/" + referralId, idempotencyKey);
    return execute(
        () -> {
          Instant now = clock.instant();
          drafts.configureBoundedReceiptWait();
          ReferralDraftStore.ReceiptClaim claim = drafts.claimReceipt(receiptKey, fingerprint, now);
          ReferralDraftCommandResult replay = replay(claim);
          if (replay != null) {
            return replay;
          }
          ReferralDraft current =
              drafts.lockDraft(referralId).orElseThrow(ReferralDraftCommands::notFound);
          if (!originMatches(context.scope(), current)) {
            throw notFound();
          }
          if (current.state() != ReferralState.DRAFT) {
            throw stateConflict("Only a draft Referral can be edited.");
          }
          if (current.version() != expectedVersion) {
            throw problem(
                HttpStatus.CONFLICT,
                PlatformErrorCode.REFERRAL_VERSION_CONFLICT,
                "The Referral draft changed; reload it before editing.");
          }
          requireValidDirectoryReferences(context.scope(), careRequirement);
          ReferralDraft revised =
              current.revise(patientReference, careRequirement, context.actor(), now);
          if (!drafts.updateDraft(revised, expectedVersion)) {
            throw problem(
                HttpStatus.CONFLICT,
                PlatformErrorCode.REFERRAL_VERSION_CONFLICT,
                "The Referral draft changed; reload it before editing.");
          }
          auditMutation(context, revised, "REFERRAL_DRAFT_EDIT");
          drafts.completeReceipt(
              receiptKey, HttpStatus.OK.value(), referralId, revised.version(), now);
          return new ReferralDraftCommandResult(revised, false, HttpStatus.OK.value());
        });
  }

  public ReferralSubmissionCommandResult submit(
      ExecutionContext context,
      InternalId referralId,
      long expectedVersion,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireOriginScope(context);
    if (expectedVersion < 1) {
      throw requirementInvalid("A positive expected Referral version is required.");
    }
    requireAuthority(context, ReferralAuthorities.submit(), "REFERRAL_SUBMIT");
    CommandReceiptKey receiptKey =
        commandKey(context, "referral.submit", "referrals/" + referralId, idempotencyKey);
    return execute(
        () -> {
          Instant now = clock.instant();
          drafts.configureBoundedReceiptWait();
          ReferralDraftStore.ReceiptClaim claim = drafts.claimReceipt(receiptKey, fingerprint, now);
          ReferralSubmissionCommandResult replay = replaySubmission(claim);
          if (replay != null) {
            return replay;
          }
          ReferralDraft current =
              drafts.lockDraft(referralId).orElseThrow(ReferralDraftCommands::notFound);
          if (!originMatches(context.scope(), current)) {
            throw notFound();
          }
          if (current.state() != ReferralState.DRAFT) {
            throw stateConflict("Only a draft Referral can be submitted.");
          }
          if (current.version() != expectedVersion) {
            throw versionConflict("The Referral changed; reload it before submitting.");
          }
          requireValidDirectoryReferences(context.scope(), current.careRequirement());
          ReferralDraft submitted = current.submit(context.actor(), now);
          SubmittedReferralRevision revision =
              new SubmittedReferralRevision(
                  current.id(),
                  1,
                  submitted.version(),
                  current.originOrganization(),
                  current.originFacility(),
                  current.patientReference(),
                  current.careRequirement(),
                  current.createdBy(),
                  context.actor(),
                  now,
                  Optional.empty());
          if (!drafts.submitDraft(submitted, expectedVersion, revision)) {
            throw versionConflict("The Referral changed; reload it before submitting.");
          }
          auditMutation(context, submitted, "REFERRAL_SUBMIT");
          drafts.completeReceipt(
              receiptKey, HttpStatus.OK.value(), referralId, submitted.version(), now);
          SubmittedReferralRevision persistedRevision =
              drafts
                  .findSubmittedRevision(referralId, submitted.version())
                  .orElseThrow(
                      () ->
                          new IllegalStateException("Submitted Referral revision was not stored"));
          return new ReferralSubmissionCommandResult(
              persistedRevision, false, HttpStatus.OK.value());
        });
  }

  public ReferralAmendmentCommandResult amendSubmitted(
      ExecutionContext context,
      InternalId referralId,
      long expectedVersion,
      String reasonCode,
      PatientReference patientReference,
      CareRequirement careRequirement,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(patientReference, "PatientReference");
    Objects.requireNonNull(careRequirement, "CareRequirement");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireOriginScope(context);
    requireExpectedVersion(expectedVersion, "A positive expected Referral version is required.");
    String normalizedReasonCode = requireReasonCode(reasonCode, "amendment");
    requireAuthority(context, ReferralAuthorities.amend(), "REFERRAL_AMEND");
    CommandReceiptKey receiptKey =
        commandKey(context, "referral.amend", "referrals/" + referralId, idempotencyKey);
    return execute(
        () -> {
          Instant now = clock.instant();
          drafts.configureBoundedReceiptWait();
          ReferralDraftStore.ReceiptClaim claim = drafts.claimReceipt(receiptKey, fingerprint, now);
          ReferralAmendmentCommandResult replay = replayAmendment(claim);
          if (replay != null) {
            return replay;
          }
          TransferAmendmentStatusPort.CoordinationGuard coordinationGuard;
          try {
            coordinationGuard =
                transferAmendmentStatus.acquireCoordinationGuard(context, referralId);
          } catch (TransferAmendmentStatusUnavailableException unavailable) {
            throw problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                PlatformErrorCode.REFERRAL_UNAVAILABLE,
                "Transfer coordination could not serialize the Referral amendment safely.");
          }
          registerGuardReleaseAfterTransaction(coordinationGuard);
          ReferralDraft current =
              drafts.lockDraft(referralId).orElseThrow(ReferralDraftCommands::notFound);
          if (!originMatches(context.scope(), current)) {
            throw notFound();
          }
          if (current.state() == ReferralState.RESOLVED) {
            throw alreadyResolved();
          }
          if (current.state() != ReferralState.SUBMITTED) {
            throw stateConflict("Only a submitted Referral can be amended.");
          }
          if (current.version() != expectedVersion) {
            throw versionConflict("The Referral changed; reload it before amending.");
          }
          try {
            if (transferAmendmentStatus.hasActiveCoordination(context, referralId)) {
              throw problem(
                  HttpStatus.CONFLICT,
                  PlatformErrorCode.REFERRAL_AMENDMENT_BLOCKED,
                  "The submitted Referral cannot be amended while Transfer coordination is active.");
            }
          } catch (TransferAmendmentStatusUnavailableException unavailable) {
            throw problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                PlatformErrorCode.REFERRAL_UNAVAILABLE,
                "Transfer coordination status could not be verified; the Referral was not amended.");
          }
          requireValidDirectoryReferences(context.scope(), careRequirement);
          SubmittedReferralRevision previous =
              drafts
                  .findSubmittedRevision(referralId, expectedVersion)
                  .orElseThrow(
                      () -> new IllegalStateException("Current submitted revision was not stored"));
          ReferralDraft amended =
              current.amendSubmitted(patientReference, careRequirement, context.actor(), now);
          SubmittedReferralRevision revision =
              new SubmittedReferralRevision(
                  current.id(),
                  Math.addExact(previous.revisionNumber(), 1),
                  amended.version(),
                  current.originOrganization(),
                  current.originFacility(),
                  patientReference,
                  careRequirement,
                  current.createdBy(),
                  context.actor(),
                  now,
                  Optional.of(normalizedReasonCode));
          if (!drafts.amendSubmitted(amended, expectedVersion, revision)) {
            throw versionConflict("The Referral changed; reload it before amending.");
          }
          auditMutation(context, amended, "REFERRAL_AMEND", Optional.of(normalizedReasonCode));
          drafts.completeReceipt(
              receiptKey, HttpStatus.OK.value(), referralId, amended.version(), now);
          SubmittedReferralRevision persisted =
              drafts
                  .findSubmittedRevision(referralId, amended.version())
                  .orElseThrow(
                      () -> new IllegalStateException("Amended Referral revision was not stored"));
          return new ReferralAmendmentCommandResult(persisted, false, HttpStatus.OK.value());
        });
  }

  public ReferralResolutionCommandResult resolve(
      ExecutionContext context,
      InternalId referralId,
      long expectedVersion,
      ReferralResolutionOutcome outcome,
      String reasonCode,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(outcome, "resolution outcome");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireOriginScope(context);
    requireExpectedVersion(expectedVersion, "A positive expected Referral version is required.");
    String normalizedReasonCode = requireReasonCode(reasonCode, "resolution");
    requireAuthority(context, ReferralAuthorities.resolve(), "REFERRAL_RESOLVE");
    CommandReceiptKey receiptKey =
        commandKey(context, "referral.resolve", "referrals/" + referralId, idempotencyKey);
    return execute(
        () -> {
          Instant now = clock.instant();
          drafts.configureBoundedReceiptWait();
          ReferralDraftStore.ReceiptClaim claim = drafts.claimReceipt(receiptKey, fingerprint, now);
          ReferralResolutionCommandResult replay = replayResolution(claim, context);
          if (replay != null) {
            return replay;
          }
          ReferralDraft current =
              drafts.lockDraft(referralId).orElseThrow(ReferralDraftCommands::notFound);
          if (!originMatches(context.scope(), current)) {
            throw notFound();
          }
          if (current.state() == ReferralState.RESOLVED) {
            throw alreadyResolved();
          }
          if (current.state() != ReferralState.SUBMITTED) {
            throw stateConflict("Only a submitted Referral can be resolved.");
          }
          if (current.version() != expectedVersion) {
            throw versionConflict("The Referral changed; reload it before resolving.");
          }
          // Phase 5 has no Transfer context. This is the authorized origin-side closure path;
          // Phase 7 will supply Transfer-owned outcomes and active-journey coordination.
          ReferralDraft resolved = current.resolve(context.actor(), now);
          ReferralResolution resolution =
              new ReferralResolution(
                  referralId,
                  outcome,
                  normalizedReasonCode,
                  context.actor(),
                  now,
                  resolved.version());
          if (!drafts.resolveDraft(resolved, expectedVersion, resolution)) {
            throw versionConflict("The Referral changed; reload it before resolving.");
          }
          auditMutation(context, resolved, "REFERRAL_RESOLVE", Optional.of(normalizedReasonCode));
          drafts.completeReceipt(
              receiptKey, HttpStatus.OK.value(), referralId, resolved.version(), now);
          ReferralResolution persisted =
              drafts
                  .findResolution(referralId)
                  .orElseThrow(
                      () -> new IllegalStateException("Referral resolution was not stored"));
          return new ReferralResolutionCommandResult(
              resolved, persisted, false, HttpStatus.OK.value());
        });
  }

  public ReferralDraftCommandResult cancel(
      ExecutionContext context,
      InternalId referralId,
      long expectedVersion,
      String reasonCode,
      String idempotencyKey,
      RequestFingerprint fingerprint) {
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    requireOriginScope(context);
    requireExpectedVersion(expectedVersion, "A positive expected Referral version is required.");
    String normalizedReasonCode = requireReasonCode(reasonCode, "cancel");
    requireAuthority(context, ReferralAuthorities.cancel(), "REFERRAL_CANCEL");
    CommandReceiptKey receiptKey =
        commandKey(context, "referral.cancel", "referrals/" + referralId, idempotencyKey);
    return execute(
        () -> {
          Instant now = clock.instant();
          drafts.configureBoundedReceiptWait();
          ReferralDraftStore.ReceiptClaim claim = drafts.claimReceipt(receiptKey, fingerprint, now);
          ReferralDraftCommandResult replay = replay(claim);
          if (replay != null) {
            return replay;
          }
          ReferralDraft current =
              drafts.lockDraft(referralId).orElseThrow(ReferralDraftCommands::notFound);
          if (!originMatches(context.scope(), current)) {
            throw notFound();
          }
          if (current.state() == ReferralState.CANCELLED) {
            throw alreadyCancelled();
          }
          if (current.state() == ReferralState.RESOLVED) {
            throw alreadyResolved();
          }
          if (current.version() != expectedVersion) {
            throw versionConflict("The Referral changed; reload it before cancelling.");
          }
          Optional<SubmittedReferralRevision> currentRevision =
              current.state() == ReferralState.SUBMITTED
                  ? Optional.of(
                      drafts
                          .findSubmittedRevision(referralId, current.version())
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Current submitted Referral revision was not stored")))
                  : Optional.empty();
          ReferralDraft cancelled = current.cancel(context.actor(), now);
          ReferralCancellation cancellation =
              new ReferralCancellation(
                  referralId, normalizedReasonCode, context.actor(), now, cancelled.version());
          if (!drafts.cancelDraft(cancelled, expectedVersion, cancellation)) {
            throw versionConflict("The Referral changed; reload it before cancelling.");
          }
          auditMutation(context, cancelled, "REFERRAL_CANCEL", Optional.of(normalizedReasonCode));
          currentRevision.ifPresent(
              revision ->
                  recordCancellationEvent(context, cancelled, revision, normalizedReasonCode, now));
          drafts.completeReceipt(
              receiptKey, HttpStatus.OK.value(), referralId, cancelled.version(), now);
          return new ReferralDraftCommandResult(cancelled, false, HttpStatus.OK.value());
        });
  }

  private void recordCancellationEvent(
      ExecutionContext context,
      ReferralDraft cancelled,
      SubmittedReferralRevision revision,
      String reasonCode,
      Instant recordedAt) {
    var payload = objectMapper.createObjectNode();
    payload.put("referralReference", cancelled.id().toString());
    payload.put("revisionNumber", revision.revisionNumber());
    payload.put("reasonCode", reasonCode);
    payload.put("sourceKind", context.sourceKind().name());
    EventType eventType = EventType.parse("referral.cancelled.v1");
    EventEnvelope event =
        new EventEnvelope(
            InternalId.generate(),
            eventType,
            "referral",
            new OccurredAt(recordedAt),
            new RecordedAt(recordedAt),
            context,
            Optional.of(new AggregatePosition("referral/" + cancelled.id(), cancelled.version())),
            payload);
    outboxWriter.recordForDeclaredFutureConsumer(event);
  }

  /** Records an invalid execution-context attempt against a non-sensitive platform target. */
  public void recordInvalidScope(ActorReference actor, CorrelationId correlationId) {
    recordSecurityOutcome(
        actor,
        ExecutionScope.platform(),
        correlationId,
        "REFERRAL_SCOPE_REJECTED",
        "INVALID_SCOPE",
        AuditOutcome.DENIED,
        AuditAuthorizationOutcome.NOT_EVALUATED);
  }

  private ReferralDraftCommandResult replay(ReferralDraftStore.ReceiptClaim claim) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The idempotency key was already used for a different Referral command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
      case REPLAY -> {
        InternalId referralId = claim.resultReference().orElseThrow();
        ReferralDraft draft =
            drafts.findDraft(referralId).orElseThrow(ReferralDraftCommands::notFound);
        yield new ReferralDraftCommandResult(
            draft, true, claim.responseStatus().orElse(HttpStatus.OK.value()));
      }
    };
  }

  private ReferralSubmissionCommandResult replaySubmission(ReferralDraftStore.ReceiptClaim claim) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The idempotency key was already used for a different Referral command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
      case REPLAY -> {
        InternalId referralId = claim.resultReference().orElseThrow();
        long referralVersion = claim.resultVersion().orElseThrow();
        SubmittedReferralRevision revision =
            drafts
                .findSubmittedRevision(referralId, referralVersion)
                .orElseThrow(ReferralDraftCommands::notFound);
        yield new ReferralSubmissionCommandResult(
            revision, true, claim.responseStatus().orElse(HttpStatus.OK.value()));
      }
    };
  }

  private ReferralAmendmentCommandResult replayAmendment(ReferralDraftStore.ReceiptClaim claim) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The idempotency key was already used for a different Referral command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
      case REPLAY -> {
        InternalId referralId = claim.resultReference().orElseThrow();
        long referralVersion = claim.resultVersion().orElseThrow();
        SubmittedReferralRevision revision =
            drafts
                .findSubmittedRevision(referralId, referralVersion)
                .orElseThrow(ReferralDraftCommands::notFound);
        yield new ReferralAmendmentCommandResult(
            revision, true, claim.responseStatus().orElse(HttpStatus.OK.value()));
      }
    };
  }

  private ReferralResolutionCommandResult replayResolution(
      ReferralDraftStore.ReceiptClaim claim, ExecutionContext context) {
    return switch (claim.state()) {
      case OWNER -> null;
      case KEY_REUSED ->
          throw problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
              "The idempotency key was already used for a different Referral command.");
      case IN_PROGRESS ->
          throw problem(
              HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
      case REPLAY -> {
        InternalId referralId = claim.resultReference().orElseThrow();
        ReferralDraft referral =
            drafts.findDraft(referralId).orElseThrow(ReferralDraftCommands::notFound);
        if (!originMatches(context.scope(), referral)) {
          throw notFound();
        }
        ReferralResolution resolution =
            drafts.findResolution(referralId).orElseThrow(ReferralDraftCommands::notFound);
        yield new ReferralResolutionCommandResult(
            referral, resolution, true, claim.responseStatus().orElse(HttpStatus.OK.value()));
      }
    };
  }

  private void requireAuthority(
      ExecutionContext context, FunctionalAuthority authority, String actionCode) {
    AuthorizationDecision decision = authorization.getObject().evaluate(context, authority.code());
    if (decision.isAllowed()) {
      return;
    }
    recordSecurityOutcome(
        context,
        actionCode,
        "AUTHORIZATION_DENIED",
        AuditOutcome.DENIED,
        AuditAuthorizationOutcome.DENIED);
    if (decision.denialReason().orElse(null)
        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Authorization could not be evaluated safely.");
    }
    throw problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated actor is not authorized for this Referral operation.");
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
          "Referral draft commands require an explicit Directory origin scope.");
    }
    try {
      InternalId.parse(scope.organization().orElseThrow().value());
      scope.facility().ifPresent(reference -> InternalId.parse(reference.value()));
    } catch (IllegalArgumentException malformed) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Referral draft commands require a valid Directory origin scope.");
    }
  }

  private void requireValidDirectoryReferences(
      ExecutionScope scope, CareRequirement careRequirement) {
    ExternalReference organization = scope.organization().orElseThrow();
    boolean activeOrigin =
        scope.facility().isPresent()
            ? directoryReferences.isActiveFacilityInOrganization(
                organization, scope.facility().orElseThrow())
            : directoryReferences.isActiveOrganization(organization);
    if (!activeOrigin) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.REFERRAL_ORIGIN_INVALID,
          "The Referral origin is not active in Directory.");
    }
    for (String code : careRequirement.requiredCareCapabilityCodes()) {
      if (!directoryReferences.isActiveCareCapabilityCode(code)) {
        throw problem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            PlatformErrorCode.REFERRAL_CAPABILITY_UNKNOWN,
            "A required CareCapability code is unknown or inactive.");
      }
    }
    careRequirement
        .selectedDestinationOrganization()
        .filter(reference -> !directoryReferences.isActiveOrganization(reference))
        .ifPresent(
            ignored -> {
              throw problem(
                  HttpStatus.UNPROCESSABLE_ENTITY,
                  PlatformErrorCode.REFERRAL_ORIGIN_INVALID,
                  "The selected destination Organization is not active in Directory.");
            });
  }

  private static boolean originMatches(ExecutionScope scope, ReferralDraft draft) {
    return scope.organization().equals(Optional.of(draft.originOrganization()))
        && scope.facility().equals(draft.originFacility());
  }

  private void auditMutation(ExecutionContext context, ReferralDraft draft, String actionCode) {
    auditMutation(context, draft, actionCode, Optional.empty());
  }

  private void auditMutation(
      ExecutionContext context,
      ReferralDraft draft,
      String actionCode,
      Optional<String> reasonCode) {
    try {
      auditWriter
          .getObject()
          .recordMutation(
              new AuditEvidence(
                  AuditActionKind.PROTECTED_MUTATION,
                  Optional.of(context.actor()),
                  Optional.of(context.scope()),
                  actionCode,
                  "Referral",
                  Optional.of(draft.id()),
                  clock.instant(),
                  AuditOutcome.SUCCEEDED,
                  reasonCode,
                  authenticationEvidence(context.authenticationMechanism()),
                  AuditAuthorizationOutcome.ALLOWED,
                  context.correlationId(),
                  Optional.of(targetContext(context.scope()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The Referral draft and its required audit evidence could not be committed.");
    }
  }

  private static void registerGuardReleaseAfterTransaction(
      TransferAmendmentStatusPort.CoordinationGuard guard) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      guard.close();
      throw new IllegalStateException(
          "Transfer coordination guard requires an active Referral transaction");
    }
    try {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              guard.close();
            }
          });
    } catch (RuntimeException failure) {
      guard.close();
      throw failure;
    }
  }

  private void recordSecurityOutcome(
      ExecutionContext context,
      String actionCode,
      String reasonCode,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome) {
    recordSecurityOutcome(
        context.actor(),
        context.scope(),
        context.correlationId(),
        actionCode,
        reasonCode,
        outcome,
        authorizationOutcome);
  }

  private void recordSecurityOutcome(
      ActorReference actor,
      ExecutionScope scope,
      CorrelationId correlationId,
      String actionCode,
      String reasonCode,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome) {
    try {
      auditWriter
          .getObject()
          .recordNonMutating(
              new AuditEvidence(
                  AuditActionKind.SECURITY_OUTCOME,
                  Optional.of(actor),
                  Optional.of(scope),
                  actionCode,
                  "Referral",
                  Optional.empty(),
                  clock.instant(),
                  outcome,
                  Optional.of(reasonCode),
                  authenticationEvidence(
                      actor.kind() == ActorKind.HUMAN
                          ? AuthenticationMechanism.HUMAN_ACCESS_TOKEN
                          : AuthenticationMechanism.SERVICE_CLIENT_CREDENTIALS),
                  authorizationOutcome,
                  correlationId));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The request remains denied; its required audit record is unavailable.");
    }
  }

  private CommandReceiptKey commandKey(
      ExecutionContext context, String operation, String target, String rawIdempotencyKey) {
    try {
      return CommandReceiptKey.from(context, operation, target, rawIdempotencyKey);
    } catch (IllegalArgumentException invalid) {
      throw requirementInvalid(
          "The Idempotency-Key must contain 1 to 128 visible ASCII characters.");
    }
  }

  private <T> T execute(java.util.function.Supplier<T> operation) {
    try {
      return transaction.execute(status -> operation.get());
    } catch (CannotAcquireLockException busy) {
      throw problem(
          HttpStatus.CONFLICT, PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS, COMMAND_BUSY_DETAIL);
    } catch (DataAccessException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.REFERRAL_UNAVAILABLE,
          "The Referral command could not be committed safely.");
    }
  }

  private static AuditTargetContext targetContext(ExecutionScope scope) {
    return new AuditTargetContext(scope.kind(), scope.organization(), scope.facility());
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

  private static PlatformProblemException notFound() {
    return problem(
        HttpStatus.NOT_FOUND,
        PlatformErrorCode.REFERRAL_NOT_FOUND,
        "The Referral draft does not exist in the authorized origin scope.");
  }

  private static PlatformProblemException requirementInvalid(String detail) {
    return problem(HttpStatus.BAD_REQUEST, PlatformErrorCode.REFERRAL_REQUIREMENT_INVALID, detail);
  }

  private static void requireExpectedVersion(long expectedVersion, String detail) {
    if (expectedVersion < 1) {
      throw requirementInvalid(detail);
    }
  }

  private static String requireReasonCode(String reasonCode, String operation) {
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw requirementInvalid(
          "A nonblank uppercase coded reason is required to " + operation + " a Referral.");
    }
    return reasonCode;
  }

  private static PlatformProblemException stateConflict(String detail) {
    return problem(HttpStatus.CONFLICT, PlatformErrorCode.REFERRAL_STATE_CONFLICT, detail);
  }

  private static PlatformProblemException alreadyResolved() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.REFERRAL_ALREADY_RESOLVED,
        "The Referral is already resolved and cannot be changed.");
  }

  private static PlatformProblemException alreadyCancelled() {
    return problem(
        HttpStatus.CONFLICT,
        PlatformErrorCode.REFERRAL_ALREADY_CANCELLED,
        "The Referral is already cancelled and cannot be changed.");
  }

  private static PlatformProblemException versionConflict(String detail) {
    return problem(HttpStatus.CONFLICT, PlatformErrorCode.REFERRAL_VERSION_CONFLICT, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
