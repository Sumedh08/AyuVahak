package org.ayuvahak.referral.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.referral.domain.PatientReference;
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralState;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/** Referral-owned policy, audit, and transient projection flow for patient-context reads. */
@Service
public final class ReferralPatientContextQueries {

  private static final String DISCLOSURE_ACTION = "REFERRAL_PATIENT_CONTEXT_DISCLOSURE";
  private static final Set<PatientContextCategory> KNOWN_CATEGORIES =
      Set.of(
          PatientContextCategory.ALLERGIES,
          PatientContextCategory.PROBLEMS,
          PatientContextCategory.MEDICATIONS);

  private final ReferralDraftStore drafts;
  private final TransferPatientContextParticipationPort transferParticipation;
  private final AuthorizationService authorization;
  private final AuditWriter auditWriter;
  private final Optional<PatientContextDisclosurePolicy> policy;
  private final Optional<ReferralPatientContextSourcePort> source;
  private final TransactionTemplate ownerRead;
  private final Clock clock;

  public ReferralPatientContextQueries(
      @Lazy ReferralDraftStore drafts,
      TransferPatientContextParticipationPort transferParticipation,
      AuthorizationService authorization,
      AuditWriter auditWriter,
      Optional<PatientContextDisclosurePolicy> policy,
      Optional<ReferralPatientContextSourcePort> source,
      @Lazy PlatformTransactionManager transactionManager,
      @Qualifier("auditClock") Clock clock) {
    this.drafts = Objects.requireNonNull(drafts, "Referral draft store");
    this.transferParticipation =
        Objects.requireNonNull(transferParticipation, "Transfer participant contract");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.auditWriter = Objects.requireNonNull(auditWriter, "audit writer");
    this.policy = Objects.requireNonNull(policy, "disclosure policy provider");
    this.source = Objects.requireNonNull(source, "patient-context source provider");
    this.clock = Objects.requireNonNull(clock, "audit clock");
    ownerRead = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    ownerRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    ownerRead.setReadOnly(true);
    ownerRead.setTimeout(5);
  }

  /** Performs current-participant verification and Referral's independent disclosure decision. */
  public PatientContextResult retrieve(
      ExecutionContext context, InternalId referralId, ReferralPatientContextRequest request) {
    Objects.requireNonNull(referralId, "Referral id");
    Objects.requireNonNull(request, "patient-context request");
    requireReceivingFacilityScope(context);

    ReferralTransferParticipation participation;
    try {
      Optional<ReferralTransferParticipation> verified =
          transferParticipation.verifyCurrentParticipant(
              context, referralId, request.transferId(), request.requestAttemptId());
      if (verified.isEmpty()) {
        deny(context, referralId, "TRANSFER_PARTICIPATION_DENIED");
        throw denied();
      }
      participation = verified.orElseThrow();
    } catch (TransferPatientContextParticipationUnavailableException unavailable) {
      recordFailure(context, referralId, "TRANSFER_PARTICIPATION_UNAVAILABLE");
      throw unavailable("Transfer could not verify the selected receiving participant.");
    }

    if (!participation.referralId().equals(referralId)
        || !participation.transferId().equals(request.transferId())
        || !participation.requestAttemptId().equals(request.requestAttemptId())
        || !participation.receivingFacility().equals(context.scope().facility().orElseThrow())) {
      deny(context, referralId, "TRANSFER_PARTICIPATION_MISMATCH");
      throw denied();
    }

    AuthorizationDecision authorizationDecision =
        authorization.evaluate(context, ReferralAuthorities.patientContextDisclosure().code());
    if (!authorizationDecision.isAllowed()) {
      deny(context, referralId, authorizationDenialReason(authorizationDecision));
      if (authorizationDecision.denialReason().orElse(null)
          == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
        throw new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
            "Referral could not authorize patient-context disclosure safely.");
      }
      throw denied();
    }

    SubmittedReferralRevision revision =
        readCurrentSubmittedRevision(context, referralId, participation);
    PatientContextDisclosureRequest policyRequest =
        new PatientContextDisclosureRequest(
            context,
            referralId,
            revision.revisionNumber(),
            request.transferId(),
            request.requestAttemptId(),
            request.purposeCode(),
            revision.patientReference());
    PatientContextDisclosureDecision disclosureDecision =
        evaluatePolicy(policyRequest, context, referralId);
    if (disclosureDecision.outcome() != PatientContextDisclosureDecision.Outcome.ALLOWED) {
      String reasonCode =
          disclosureDecision.outcome() == PatientContextDisclosureDecision.Outcome.DENIED
              ? "DISCLOSURE_POLICY_DENIED"
              : "DISCLOSURE_POLICY_INDETERMINATE";
      deny(context, referralId, reasonCode);
      if (disclosureDecision.outcome() == PatientContextDisclosureDecision.Outcome.INDETERMINATE) {
        throw new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
            "Referral could not establish the patient-context disclosure policy safely.");
      }
      throw denied();
    }

    // This durable audit is intentionally committed before any clinical source operation.
    recordAuthorizedRead(context, referralId, request.purposeCode());
    PatientContextSourceRequest sourceRequest =
        new PatientContextSourceRequest(
            revision.patientReference(),
            request.purposeCode(),
            disclosureDecision.permittedCategories(),
            context.correlationId());
    PatientContextSourceDocument document = retrieveSource(sourceRequest);
    PatientContextResult result =
        project(document, revision.patientReference(), disclosureDecision);
    recordSourceOutcome(context, referralId, result.status());
    return result;
  }

  /** Records rejected request-scope input without attributing an unvalidated scope. */
  public void recordInvalidScope(ActorReference actor, CorrelationId correlationId) {
    Objects.requireNonNull(actor, "verified actor");
    Objects.requireNonNull(correlationId, "correlation id");
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.of(actor),
              Optional.empty(),
              "REFERRAL_PATIENT_CONTEXT_SCOPE_REJECTED",
              "Referral",
              Optional.empty(),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of("INVALID_EXECUTION_SCOPE"),
              authentication(actor.kind()),
              AuditAuthorizationOutcome.NOT_EVALUATED,
              correlationId,
              Optional.empty()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Referral withheld patient context because required access audit is unavailable.");
    }
  }

  private SubmittedReferralRevision readCurrentSubmittedRevision(
      ExecutionContext context,
      InternalId referralId,
      ReferralTransferParticipation participation) {
    Optional<SubmittedRead> found;
    try {
      found =
          Objects.requireNonNull(
              ownerRead.execute(
                  ignored -> {
                    Optional<ReferralDraft> current = drafts.findDraft(referralId);
                    Optional<SubmittedReferralRevision> latest =
                        drafts.findLatestSubmittedRevision(referralId);
                    if (current.isEmpty() || latest.isEmpty()) {
                      return Optional.empty();
                    }
                    return Optional.of(
                        new SubmittedRead(
                            current.orElseThrow(),
                            latest.orElseThrow(),
                            drafts.isIdentifierRestricted(referralId)));
                  }),
              "Referral patient-context read result");
    } catch (DataAccessException | TransactionTimedOutException unavailable) {
      recordFailure(context, referralId, "REFERRAL_READ_UNAVAILABLE");
      throw unavailable("Referral could not load its current patient reference safely.");
    }
    if (found.isEmpty()) {
      recordFailure(context, referralId, "REFERRAL_REVISION_NOT_FOUND");
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.REFERRAL_NOT_FOUND,
          "The requested patient context is unavailable for this Referral.");
    }

    SubmittedRead read = found.orElseThrow();
    if (read.identifierRestricted()) {
      deny(context, referralId, "PATIENT_REFERENCE_RESTRICTED");
      throw denied();
    }
    ReferralDraft current = read.current();
    SubmittedReferralRevision revision = read.revision();
    if (current.state() != ReferralState.SUBMITTED
        || current.version() != revision.referralVersion()
        || !current.patientReference().equals(revision.patientReference())
        || !revision.referralId().equals(referralId)
        || revision.revisionNumber() != participation.referralRevision()) {
      deny(context, referralId, "REFERRAL_REVISION_NOT_CURRENT");
      throw denied();
    }
    return revision;
  }

  private PatientContextDisclosureDecision evaluatePolicy(
      PatientContextDisclosureRequest request, ExecutionContext context, InternalId referralId) {
    if (policy.isEmpty()) {
      deny(context, referralId, "DISCLOSURE_POLICY_UNAVAILABLE");
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Referral patient-context disclosure policy is not configured.");
    }
    try {
      PatientContextDisclosureDecision decision =
          Objects.requireNonNull(policy.orElseThrow().evaluate(request), "disclosure decision");
      if (!KNOWN_CATEGORIES.containsAll(decision.permittedCategories())) {
        throw new IllegalStateException("disclosure policy returned an unsupported category");
      }
      return decision;
    } catch (RuntimeException unavailable) {
      deny(context, referralId, "DISCLOSURE_POLICY_UNAVAILABLE");
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Referral could not evaluate the patient-context disclosure policy safely.");
    }
  }

  private PatientContextSourceDocument retrieveSource(PatientContextSourceRequest request) {
    if (source.isEmpty()) {
      return null;
    }
    try {
      return source.orElseThrow().retrieve(request);
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  private static PatientContextResult project(
      PatientContextSourceDocument document,
      PatientReference expectedPatient,
      PatientContextDisclosureDecision decision) {
    if (document == null) {
      return unavailableResult();
    }
    if (!expectedPatient.equals(document.subject())) {
      return new PatientContextResult(
          PatientContextResult.Status.INVALID, List.of(), Optional.of(document.evidence()));
    }
    if (document.status() != PatientContextSourceDocument.Status.AVAILABLE) {
      PatientContextResult.Status status =
          switch (document.status()) {
            case UNAVAILABLE -> PatientContextResult.Status.UNAVAILABLE;
            case INVALID -> PatientContextResult.Status.INVALID;
            case STALE -> PatientContextResult.Status.STALE;
            case AVAILABLE -> throw new IllegalStateException("unreachable source status");
          };
      return new PatientContextResult(status, List.of(), Optional.of(document.evidence()));
    }
    if (document.evidence().freshness() != PatientContextSourceEvidence.Freshness.CURRENT) {
      PatientContextResult.Status status =
          document.evidence().freshness() == PatientContextSourceEvidence.Freshness.STALE
              ? PatientContextResult.Status.STALE
              : PatientContextResult.Status.UNAVAILABLE;
      return new PatientContextResult(status, List.of(), Optional.of(document.evidence()));
    }
    List<PatientContextSection> allowed =
        document.sections().stream()
            .filter(section -> decision.permittedCategories().contains(section.category()))
            .toList();
    return new PatientContextResult(
        PatientContextResult.Status.AVAILABLE, allowed, Optional.of(document.evidence()));
  }

  private static PatientContextResult unavailableResult() {
    return new PatientContextResult(
        PatientContextResult.Status.UNAVAILABLE, List.of(), Optional.empty());
  }

  private void deny(ExecutionContext context, InternalId referralId, String reasonCode) {
    record(
        context,
        referralId,
        AuditActionKind.SECURITY_OUTCOME,
        AuditOutcome.DENIED,
        AuditAuthorizationOutcome.DENIED,
        Optional.of(reasonCode));
  }

  private void recordFailure(ExecutionContext context, InternalId referralId, String reasonCode) {
    record(
        context,
        referralId,
        AuditActionKind.SECURITY_OUTCOME,
        AuditOutcome.FAILED,
        AuditAuthorizationOutcome.NOT_EVALUATED,
        Optional.of(reasonCode));
  }

  private void recordAuthorizedRead(
      ExecutionContext context, InternalId referralId, String purposeCode) {
    record(
        context,
        referralId,
        AuditActionKind.SENSITIVE_READ,
        AuditOutcome.SUCCEEDED,
        AuditAuthorizationOutcome.ALLOWED,
        Optional.of("PURPOSE_" + purposeCode));
  }

  private void recordSourceOutcome(
      ExecutionContext context, InternalId referralId, PatientContextResult.Status status) {
    AuditOutcome outcome =
        status == PatientContextResult.Status.AVAILABLE
            ? AuditOutcome.SUCCEEDED
            : AuditOutcome.FAILED;
    record(
        context,
        referralId,
        AuditActionKind.SENSITIVE_READ,
        outcome,
        AuditAuthorizationOutcome.ALLOWED,
        Optional.of("SOURCE_" + status.name()));
  }

  private void record(
      ExecutionContext context,
      InternalId referralId,
      AuditActionKind kind,
      AuditOutcome outcome,
      AuditAuthorizationOutcome authorizationOutcome,
      Optional<String> reasonCode) {
    try {
      auditWriter.recordNonMutating(
          new AuditEvidence(
              kind,
              Optional.of(context.actor()),
              Optional.of(context.scope()),
              DISCLOSURE_ACTION,
              "Referral",
              Optional.of(referralId),
              clock.instant(),
              outcome,
              reasonCode,
              authentication(context.actor().kind()),
              authorizationOutcome,
              context.correlationId(),
              Optional.of(
                  new AuditTargetContext(
                      context.scope().kind(),
                      context.scope().organization(),
                      context.scope().facility()))));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "Referral withheld patient context because required access audit is unavailable.");
    }
  }

  private static AuditAuthenticationEvidence authentication(ActorKind actorKind) {
    return actorKind == ActorKind.HUMAN
        ? AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN
        : AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
  }

  private static String authorizationDenialReason(AuthorizationDecision decision) {
    return decision.denialReason().orElse(null)
            == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE
        ? "AUTHORIZATION_STORE_UNAVAILABLE"
        : "AUTHORIZATION_DENIED";
  }

  private static void requireReceivingFacilityScope(ExecutionContext context) {
    if (context == null
        || context.scope() == null
        || context.scope().kind() != ExecutionScope.Kind.FACILITY
        || context.scope().organization().isEmpty()
        || context.scope().facility().isEmpty()) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Referral patient-context disclosure requires a validated receiving facility scope.");
    }
  }

  private static PlatformProblemException denied() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated actor is not authorized to receive this patient context.");
  }

  private static PlatformProblemException unavailable(String detail) {
    return new PlatformProblemException(
        HttpStatus.SERVICE_UNAVAILABLE, PlatformErrorCode.REFERRAL_UNAVAILABLE, detail);
  }

  private record SubmittedRead(
      ReferralDraft current, SubmittedReferralRevision revision, boolean identifierRestricted) {}
}
