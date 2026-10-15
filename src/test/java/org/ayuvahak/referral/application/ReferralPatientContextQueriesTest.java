package org.ayuvahak.referral.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeValidator;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.referral.domain.CareRequirement;
import org.ayuvahak.referral.domain.PatientReference;
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralState;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class ReferralPatientContextQueriesTest {

  private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
  private static final IssuerId DIRECTORY = new IssuerId("urn:ayuvahak:directory");
  private static final IssuerId IDP = new IssuerId("https://identity.example.test/realm/synthetic");
  private static final IssuerId CLINICAL_SOURCE =
      new IssuerId("https://clinical-source.example.test/fhir");
  private static final String PATIENT_MARKER = "SYN-PRIVATE-PATIENT-MARKER-8F93";
  private static final String CLINICAL_CODE = "SYN-PRIVATE-CLINICAL-CODE-ALPHA";
  private static final String PURPOSE = "CARE_COORDINATION";

  @Test
  void returnsOnlyPolicyPermittedSectionsAndAuditsPurposeBeforeSourceRead() {
    Fixture fixture = new Fixture();
    fixture.policy =
        Optional.of(
            request -> {
              assertThat(fixture.audits).hasSize(0);
              return allowed(PatientContextCategory.ALLERGIES);
            });
    fixture.source =
        Optional.of(
            request ->
                fixture.sourceDocument(
                    PatientContextSourceDocument.Status.AVAILABLE,
                    fixture.patientReference,
                    PatientContextSourceEvidence.Freshness.CURRENT,
                    List.of(
                        section(PatientContextCategory.ALLERGIES, CLINICAL_CODE),
                        section(
                            PatientContextCategory.MEDICATIONS, "SYN-PRIVATE-MEDICATION-CODE"))));

    PatientContextResult result =
        fixture
            .queries()
            .retrieve(
                fixture.context(fixture.receivingFacility),
                fixture.referralId,
                new ReferralPatientContextRequest(fixture.transferId, fixture.attemptId, PURPOSE));

    assertThat(result.status()).isEqualTo(PatientContextResult.Status.AVAILABLE);
    assertThat(result.sections())
        .containsExactly(section(PatientContextCategory.ALLERGIES, CLINICAL_CODE));
    assertThat(fixture.audits).hasSize(2);
    assertThat(fixture.audits.getFirst().reasonCode()).contains("PURPOSE_" + PURPOSE);
    assertThat(fixture.audits.getFirst().actionKind().name()).isEqualTo("SENSITIVE_READ");
    assertThat(fixture.sourceCalls).hasValue(1);
    assertThat(result.toString()).doesNotContain(PATIENT_MARKER, CLINICAL_CODE);
    assertThat(fixture.storeWrites).hasValue(0);
  }

  @Test
  void deniesWhenTransferReportsNoCurrentSelectedParticipant() {
    Fixture fixture = new Fixture();
    fixture.participation = Optional.empty();

    assertDenied(fixture, fixture.context(fixture.receivingFacility));

    assertThat(fixture.policyCalls).hasValue(0);
    assertThat(fixture.sourceCalls).hasValue(0);
    assertThat(fixture.storeWrites).hasValue(0);
  }

  @Test
  void deniesWhenTransferParticipationDoesNotMatchSelectedFacilityOrAttempt() {
    Fixture wrongFacility = new Fixture();
    wrongFacility.participation =
        Optional.of(wrongFacility.participationAt(wrongFacility.otherFacility));
    assertDenied(wrongFacility, wrongFacility.context(wrongFacility.receivingFacility));
    assertThat(wrongFacility.sourceCalls).hasValue(0);

    Fixture wrongAttempt = new Fixture();
    wrongAttempt.participation =
        Optional.of(
            new ReferralTransferParticipation(
                wrongAttempt.referralId,
                wrongAttempt.transferId,
                InternalId.generate(),
                wrongAttempt.revisionNumber,
                wrongAttempt.receivingFacility));
    assertDenied(wrongAttempt, wrongAttempt.context(wrongAttempt.receivingFacility));
    assertThat(wrongAttempt.sourceCalls).hasValue(0);
  }

  @Test
  void deniesWhenTheCurrentReferralWasCancelledOrItsRevisionChanged() {
    Fixture cancelled = new Fixture();
    cancelled.current = cancelled.currentWithState(ReferralState.CANCELLED, 3);
    assertDenied(cancelled, cancelled.context(cancelled.receivingFacility));
    assertThat(cancelled.sourceCalls).hasValue(0);

    Fixture amended = new Fixture();
    amended.current = amended.currentWithState(ReferralState.SUBMITTED, 3);
    assertDenied(amended, amended.context(amended.receivingFacility));
    assertThat(amended.sourceCalls).hasValue(0);
  }

  @Test
  void deniesWhenReferralDisclosureGrantIsMissing() {
    Fixture fixture = new Fixture();
    fixture.grantAllowed = false;

    assertDenied(fixture, fixture.context(fixture.receivingFacility));

    assertThat(fixture.policyCalls).hasValue(0);
    assertThat(fixture.sourceCalls).hasValue(0);
    assertThat(fixture.audits)
        .singleElement()
        .satisfies(evidence -> assertThat(evidence.outcome().name()).isEqualTo("DENIED"));
  }

  @Test
  void failsClosedWhenPolicyIsMissingDeniedIndeterminateOrUnavailable() {
    Fixture missing = new Fixture();
    missing.policy = Optional.empty();
    assertUnavailable(missing);
    assertThat(missing.sourceCalls).hasValue(0);

    Fixture denied = new Fixture();
    denied.policy =
        Optional.of(
            request ->
                new PatientContextDisclosureDecision(
                    PatientContextDisclosureDecision.Outcome.DENIED, Set.of()));
    assertDenied(denied, denied.context(denied.receivingFacility));
    assertThat(denied.sourceCalls).hasValue(0);

    Fixture indeterminate = new Fixture();
    indeterminate.policy =
        Optional.of(
            request ->
                new PatientContextDisclosureDecision(
                    PatientContextDisclosureDecision.Outcome.INDETERMINATE, Set.of()));
    assertUnavailable(indeterminate);
    assertThat(indeterminate.sourceCalls).hasValue(0);

    Fixture policyThrows = new Fixture();
    policyThrows.policy =
        Optional.of(
            request -> {
              throw new IllegalStateException("synthetic policy outage");
            });
    assertUnavailable(policyThrows);
    assertThat(policyThrows.sourceCalls).hasValue(0);
  }

  @Test
  void auditFailureBeforeRetrievalPreventsCallingTheClinicalSource() {
    Fixture fixture = new Fixture();
    fixture.failAuditAt = 1;

    assertAuditUnavailable(fixture);

    assertThat(fixture.sourceCalls).hasValue(0);
  }

  @Test
  void auditFailureAfterRetrievalWithholdsTheTransientResult() {
    Fixture fixture = new Fixture();
    fixture.source =
        Optional.of(
            request ->
                fixture.sourceDocument(
                    PatientContextSourceDocument.Status.AVAILABLE,
                    fixture.patientReference,
                    PatientContextSourceEvidence.Freshness.CURRENT,
                    List.of(section(PatientContextCategory.ALLERGIES, CLINICAL_CODE))));
    fixture.failAuditAt = 2;

    assertAuditUnavailable(fixture);

    assertThat(fixture.sourceCalls).hasValue(1);
  }

  @Test
  void returnsExplicitUnavailableWhenNoLocalSourceIsConfigured() {
    Fixture fixture = new Fixture();
    fixture.source = Optional.empty();

    PatientContextResult result = fixture.retrieve();

    assertThat(result.status()).isEqualTo(PatientContextResult.Status.UNAVAILABLE);
    assertThat(result.sections()).isEmpty();
    assertThat(fixture.audits)
        .extracting(AuditEvidence::reasonCode)
        .contains(Optional.of("SOURCE_UNAVAILABLE"));
  }

  @Test
  void sourceOutcomesAndSubjectMismatchNeverReturnClinicalSections() {
    assertNoSectionsForSourceOutcome(
        PatientContextSourceDocument.Status.UNAVAILABLE,
        PatientContextSourceEvidence.Freshness.CURRENT,
        new Fixture().patientReference,
        PatientContextResult.Status.UNAVAILABLE);
    assertNoSectionsForSourceOutcome(
        PatientContextSourceDocument.Status.INVALID,
        PatientContextSourceEvidence.Freshness.CURRENT,
        new Fixture().patientReference,
        PatientContextResult.Status.INVALID);
    assertNoSectionsForSourceOutcome(
        PatientContextSourceDocument.Status.STALE,
        PatientContextSourceEvidence.Freshness.STALE,
        new Fixture().patientReference,
        PatientContextResult.Status.STALE);
    assertNoSectionsForSourceOutcome(
        PatientContextSourceDocument.Status.AVAILABLE,
        PatientContextSourceEvidence.Freshness.UNKNOWN,
        new Fixture().patientReference,
        PatientContextResult.Status.UNAVAILABLE);
    Fixture expected = new Fixture();
    assertNoSectionsForSourceOutcome(
        PatientContextSourceDocument.Status.AVAILABLE,
        PatientContextSourceEvidence.Freshness.CURRENT,
        new PatientReference(
            new ExternalReference(CLINICAL_SOURCE, "Patient", "SYN-DIFFERENT-PATIENT"),
            Optional.empty()),
        PatientContextResult.Status.INVALID,
        expected);
  }

  @Test
  void sourceRuntimeFailureIsReportedAsUnavailableWithoutFallback() {
    Fixture fixture = new Fixture();
    fixture.source =
        Optional.of(
            request -> {
              throw new IllegalStateException("synthetic source outage");
            });

    PatientContextResult result = fixture.retrieve();

    assertThat(result.status()).isEqualTo(PatientContextResult.Status.UNAVAILABLE);
    assertThat(result.sections()).isEmpty();
    assertThat(result.toString()).doesNotContain(PATIENT_MARKER, CLINICAL_CODE);
  }

  @Test
  void codeAndSectionStringFormsDoNotExposeClinicalContent() {
    PatientContextCode code = new PatientContextCode("urn:synthetic:clinical", CLINICAL_CODE);
    PatientContextSection section =
        new PatientContextSection(PatientContextCategory.ALLERGIES, List.of(code));

    assertThat(code.toString()).doesNotContain("urn:synthetic:clinical", CLINICAL_CODE);
    assertThat(section.toString()).doesNotContain("urn:synthetic:clinical", CLINICAL_CODE);
  }

  private static void assertNoSectionsForSourceOutcome(
      PatientContextSourceDocument.Status sourceStatus,
      PatientContextSourceEvidence.Freshness freshness,
      PatientReference subject,
      PatientContextResult.Status expectedStatus) {
    assertNoSectionsForSourceOutcome(
        sourceStatus, freshness, subject, expectedStatus, new Fixture());
  }

  private static void assertNoSectionsForSourceOutcome(
      PatientContextSourceDocument.Status sourceStatus,
      PatientContextSourceEvidence.Freshness freshness,
      PatientReference subject,
      PatientContextResult.Status expectedStatus,
      Fixture fixture) {
    fixture.source =
        Optional.of(
            request ->
                fixture.sourceDocument(
                    sourceStatus,
                    subject,
                    freshness,
                    sourceStatus == PatientContextSourceDocument.Status.AVAILABLE
                            && freshness == PatientContextSourceEvidence.Freshness.CURRENT
                        ? List.of(section(PatientContextCategory.ALLERGIES, CLINICAL_CODE))
                        : List.of()));

    PatientContextResult result = fixture.retrieve();

    assertThat(result.status()).isEqualTo(expectedStatus);
    assertThat(result.sections()).isEmpty();
  }

  private static PatientContextSection section(PatientContextCategory category, String code) {
    return new PatientContextSection(
        category, List.of(new PatientContextCode("urn:synthetic:clinical", code)));
  }

  private static PatientContextDisclosureDecision allowed(PatientContextCategory... categories) {
    return new PatientContextDisclosureDecision(
        PatientContextDisclosureDecision.Outcome.ALLOWED, Set.of(categories));
  }

  private static void assertDenied(Fixture fixture, ExecutionContext context) {
    assertThatThrownBy(
            () ->
                fixture
                    .queries()
                    .retrieve(
                        context,
                        fixture.referralId,
                        new ReferralPatientContextRequest(
                            fixture.transferId, fixture.attemptId, PURPOSE)))
        .isInstanceOf(PlatformProblemException.class)
        .extracting(exception -> ((PlatformProblemException) exception).code())
        .isEqualTo(PlatformErrorCode.AUTHORIZATION_DENIED);
  }

  private static void assertUnavailable(Fixture fixture) {
    assertThatThrownBy(fixture::retrieve)
        .isInstanceOf(PlatformProblemException.class)
        .extracting(exception -> ((PlatformProblemException) exception).status())
        .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
  }

  private static void assertAuditUnavailable(Fixture fixture) {
    assertThatThrownBy(fixture::retrieve)
        .isInstanceOf(PlatformProblemException.class)
        .extracting(exception -> ((PlatformProblemException) exception).code())
        .isEqualTo(PlatformErrorCode.AUDIT_UNAVAILABLE);
  }

  private static final class Fixture {
    private final InternalId referralId = InternalId.generate();
    private final InternalId transferId = InternalId.generate();
    private final InternalId attemptId = InternalId.generate();
    private final long revisionNumber = 1;
    private final ExternalReference receivingOrganization = organization();
    private final ExternalReference receivingFacility = facility();
    private final ExternalReference otherFacility = facility();
    private final ExternalReference originOrganization = organization();
    private final ActorReference actor = ActorReference.human(IDP, "synthetic-recipient");
    private final PatientReference patientReference =
        new PatientReference(
            new ExternalReference(CLINICAL_SOURCE, "Patient", PATIENT_MARKER), Optional.empty());
    private final CareRequirement requirement =
        new CareRequirement(
            List.of("SYN-CARE"),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Optional.empty(),
            Optional.empty());
    private final SubmittedReferralRevision revision =
        new SubmittedReferralRevision(
            referralId,
            revisionNumber,
            2,
            originOrganization,
            Optional.empty(),
            patientReference,
            requirement,
            actor,
            actor,
            NOW.minusSeconds(60),
            Optional.empty());
    private ReferralDraft current = currentWithState(ReferralState.SUBMITTED, 2);
    private Optional<ReferralTransferParticipation> participation =
        Optional.of(participationAt(receivingFacility));
    private Optional<PatientContextDisclosurePolicy> policy =
        Optional.of(
            request ->
                allowed(PatientContextCategory.ALLERGIES, PatientContextCategory.MEDICATIONS));
    private Optional<ReferralPatientContextSourcePort> source =
        Optional.of(
            request ->
                sourceDocument(
                    PatientContextSourceDocument.Status.AVAILABLE,
                    patientReference,
                    PatientContextSourceEvidence.Freshness.CURRENT,
                    List.of(section(PatientContextCategory.ALLERGIES, CLINICAL_CODE))));
    private final AtomicInteger policyCalls = new AtomicInteger();
    private final AtomicInteger sourceCalls = new AtomicInteger();
    private final AtomicInteger storeWrites = new AtomicInteger();
    private final List<AuditEvidence> audits = new ArrayList<>();
    private boolean grantAllowed = true;
    private int failAuditAt = -1;
    private final AuditWriter auditWriter =
        new AuditWriter(
            new AuditRecordAppender() {
              private int writes;

              @Override
              public void appendInOwnerTransaction(
                  InternalId id, AuditEvidence evidence, Instant recordedAt) {
                throw new AssertionError("patient-context reads are non-mutating");
              }

              @Override
              public void appendDurably(InternalId id, AuditEvidence evidence, Instant recordedAt) {
                writes++;
                if (writes == failAuditAt) {
                  throw new AuditPersistenceUnavailableException(
                      new IllegalStateException("synthetic audit failure"));
                }
                audits.add(evidence);
              }
            },
            Clock.fixed(NOW, java.time.ZoneOffset.UTC));

    private ReferralPatientContextQueries queries() {
      ReferralDraftStore store =
          (ReferralDraftStore)
              Proxy.newProxyInstance(
                  ReferralDraftStore.class.getClassLoader(),
                  new Class<?>[] {ReferralDraftStore.class},
                  (proxy, method, arguments) -> {
                    if (method.getName().equals("findDraft")) {
                      return Optional.of(current);
                    }
                    if (method.getName().equals("findLatestSubmittedRevision")) {
                      return Optional.of(revision);
                    }
                    if (method.getName().startsWith("insert")
                        || method.getName().startsWith("update")
                        || method.getName().startsWith("submit")
                        || method.getName().startsWith("amend")
                        || method.getName().startsWith("resolve")
                        || method.getName().startsWith("cancel")
                        || method.getName().startsWith("complete")) {
                      storeWrites.incrementAndGet();
                      throw new AssertionError(
                          "patient-context retrieval must not mutate Referral state");
                    }
                    throw new UnsupportedOperationException(method.getName());
                  });
      TransferPatientContextParticipationPort transferPort =
          (context, requestedReferral, requestedTransfer, requestedAttempt) -> participation;
      AuthorizationService authorization =
          new AuthorizationService(
              (requestedActor, authority, scope) ->
                  grantAllowed
                      && requestedActor.equals(actor)
                      && authority
                          .code()
                          .equals(ReferralAuthorities.patientContextDisclosure().code())
                      && scope.facility().filter(receivingFacility::equals).isPresent(),
              new FunctionalAuthorityCatalog(
                  List.of(ReferralAuthorities.patientContextDisclosure())));
      PatientContextDisclosurePolicy countedPolicy =
          request -> {
            policyCalls.incrementAndGet();
            return policy.orElseThrow().evaluate(request);
          };
      ReferralPatientContextSourcePort countedSource =
          request -> {
            sourceCalls.incrementAndGet();
            return source.orElseThrow().retrieve(request);
          };
      return new ReferralPatientContextQueries(
          store,
          transferPort,
          authorization,
          auditWriter,
          policy.map(ignored -> countedPolicy),
          source.map(ignored -> countedSource),
          transactions(),
          Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private PatientContextResult retrieve() {
      return queries()
          .retrieve(
              context(receivingFacility),
              referralId,
              new ReferralPatientContextRequest(transferId, attemptId, PURPOSE));
    }

    private PatientContextSourceDocument sourceDocument(
        PatientContextSourceDocument.Status status,
        PatientReference subject,
        PatientContextSourceEvidence.Freshness freshness,
        List<PatientContextSection> sections) {
      return new PatientContextSourceDocument(
          status,
          subject,
          sections,
          new PatientContextSourceEvidence(
              "SYNTHETIC_CLINICAL_SOURCE",
              Optional.of("v1"),
              Optional.of(NOW.minusSeconds(30)),
              NOW,
              "SYNTHETIC_PROJECTION_V1",
              freshness));
    }

    private ReferralTransferParticipation participationAt(ExternalReference facility) {
      return new ReferralTransferParticipation(
          referralId, transferId, attemptId, revisionNumber, facility);
    }

    private ReferralDraft currentWithState(ReferralState state, long version) {
      return new ReferralDraft(
          referralId,
          originOrganization,
          Optional.empty(),
          patientReference,
          requirement,
          actor,
          actor,
          state,
          version,
          NOW.minusSeconds(120),
          NOW.minusSeconds(60));
    }

    private ExecutionContext context(ExternalReference facility) {
      ExecutionScopeValidator syntheticScope = (ignoredActor, ignoredScope) -> true;
      return new ExecutionContextFactory(syntheticScope)
          .forInteractiveRequest(
              actor,
              RequestedExecutionScope.facility(receivingOrganization, facility),
              new CorrelationId("p8-04-referral-test"));
    }
  }

  private static ExternalReference organization() {
    return new ExternalReference(DIRECTORY, "Organization", InternalId.generate().toString());
  }

  private static ExternalReference facility() {
    return new ExternalReference(DIRECTORY, "Facility", InternalId.generate().toString());
  }

  private static PlatformTransactionManager transactions() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new org.springframework.transaction.support.SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }
}
