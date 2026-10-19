package org.ayuvahak.referral.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.referral.application.PatientContextCategory;
import org.ayuvahak.referral.application.PatientContextCode;
import org.ayuvahak.referral.application.PatientContextDisclosureDecision;
import org.ayuvahak.referral.application.PatientContextDisclosurePolicy;
import org.ayuvahak.referral.application.PatientContextSection;
import org.ayuvahak.referral.application.PatientContextSourceDocument;
import org.ayuvahak.referral.application.PatientContextSourceEvidence;
import org.ayuvahak.referral.application.ReferralDraftStore;
import org.ayuvahak.referral.application.ReferralPatientContextQueries;
import org.ayuvahak.referral.application.ReferralPatientContextSourcePort;
import org.ayuvahak.referral.application.ReferralTransferParticipation;
import org.ayuvahak.referral.application.TransferPatientContextParticipationPort;
import org.ayuvahak.referral.domain.CareRequirement;
import org.ayuvahak.referral.domain.PatientReference;
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.ayuvahak.referral.domain.ReferralDraft;
import org.ayuvahak.referral.domain.ReferralState;
import org.ayuvahak.referral.domain.SubmittedReferralRevision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ReferralPatientContextApiTest {

  private static final String PATIENT_MARKER = "SYN-PRIVATE-PATIENT-MARKER-8F93";
  private static final String NARRATIVE_MARKER = "SYN-PRIVATE-NARRATIVE-CONTENT";
  private static final ActorReference ACTOR =
      ActorReference.human(
          new IssuerId("https://identity.example.test/realm/synthetic"), "synthetic-recipient");

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void authorizedPurposeRouteReturnsOnlyTheReferralAllowlistedProjection() throws Exception {
    InternalId referralId = InternalId.generate();
    InternalId transferId = InternalId.generate();
    InternalId attemptId = InternalId.generate();
    String organizationId = InternalId.generate().toString();
    String facilityId = InternalId.generate().toString();
    ExternalReference receivingFacility = directoryReference("Facility", facilityId);
    PatientReference patientReference = patientReference();
    ReferralPatientContextQueries patientContextQueries =
        patientContextQueries(
            referralId, transferId, attemptId, receivingFacility, patientReference);
    ReferralPatientContextController controller =
        new ReferralPatientContextController(
            patientContextQueries, new ExecutionContextFactory((actor, requestedScope) -> true));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
    setAuthenticatedPrincipal();

    String response =
        mvc.perform(
                get("/api/v1/referrals/{referralId}/patient-context", referralId)
                    .header("X-AyuVahak-Execution-Scope", "FACILITY")
                    .header("X-AyuVahak-Organization-Id", organizationId)
                    .header("X-AyuVahak-Facility-Id", facilityId)
                    .param("transferId", transferId.toString())
                    .param("requestAttemptId", attemptId.toString())
                    .param("purposeCode", "CARE_COORDINATION"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.sections[0].category").value("PROBLEMS"))
            .andExpect(jsonPath("$.sections[0].codes[0].code").value("SYN-PROBLEM-001"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(response)
        .doesNotContain(
            PATIENT_MARKER, NARRATIVE_MARKER, "patientReference", "bundle", "resourceType");
    assertThat(response).contains("SYN-PROBLEM-001", "CURRENT", "SYNTHETIC_IPS_SOURCE");
  }

  private static ReferralPatientContextQueries patientContextQueries(
      InternalId referralId,
      InternalId transferId,
      InternalId attemptId,
      ExternalReference receivingFacility,
      PatientReference patientReference) {
    Instant now = Instant.parse("2026-10-15T08:00:00Z");
    ExternalReference originOrganization = directoryReference("Organization", "SYN-ORG-001");
    CareRequirement careRequirement =
        new CareRequirement(
            List.of("SYN-CARE"),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Optional.empty(),
            Optional.empty());
    ReferralDraft current =
        new ReferralDraft(
            referralId,
            originOrganization,
            Optional.empty(),
            patientReference,
            careRequirement,
            ACTOR,
            ACTOR,
            ReferralState.SUBMITTED,
            2,
            now.minusSeconds(120),
            now.minusSeconds(60));
    SubmittedReferralRevision revision =
        new SubmittedReferralRevision(
            referralId,
            1,
            2,
            originOrganization,
            Optional.empty(),
            patientReference,
            careRequirement,
            ACTOR,
            ACTOR,
            now.minusSeconds(60),
            Optional.empty());
    ReferralDraftStore store =
        (ReferralDraftStore)
            Proxy.newProxyInstance(
                ReferralDraftStore.class.getClassLoader(),
                new Class<?>[] {ReferralDraftStore.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "findDraft" -> Optional.of(current);
                      case "findLatestSubmittedRevision" -> Optional.of(revision);
                      case "isIdentifierRestricted" -> false;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    AuthorizationService authorization =
        new AuthorizationService(
            (actor, authority, scope) ->
                actor.equals(ACTOR)
                    && authority
                        .code()
                        .equals(ReferralAuthorities.patientContextDisclosure().code())
                    && scope.kind() == ExecutionScope.Kind.FACILITY
                    && scope.facility().filter(receivingFacility::equals).isPresent(),
            new FunctionalAuthorityCatalog(
                List.of(ReferralAuthorities.patientContextDisclosure())));
    TransferPatientContextParticipationPort transferPort =
        (context, requestedReferral, requestedTransfer, requestedAttempt) ->
            requestedReferral.equals(referralId)
                    && requestedTransfer.equals(transferId)
                    && requestedAttempt.equals(attemptId)
                ? Optional.of(
                    new ReferralTransferParticipation(
                        referralId, transferId, attemptId, 1, receivingFacility))
                : Optional.empty();
    AuditWriter auditWriter =
        new AuditWriter(
            new AuditRecordAppender() {
              @Override
              public void appendInOwnerTransaction(
                  InternalId id, AuditEvidence evidence, Instant recordedAt) {
                throw new AssertionError("patient-context read is non-mutating");
              }

              @Override
              public void appendDurably(
                  InternalId id, AuditEvidence evidence, Instant recordedAt) {}
            },
            Clock.fixed(now, java.time.ZoneOffset.UTC));
    PatientContextDisclosurePolicy policy =
        request ->
            new PatientContextDisclosureDecision(
                PatientContextDisclosureDecision.Outcome.ALLOWED,
                Set.of(PatientContextCategory.PROBLEMS));
    ReferralPatientContextSourcePort source =
        request ->
            new PatientContextSourceDocument(
                PatientContextSourceDocument.Status.AVAILABLE,
                patientReference,
                List.of(
                    new PatientContextSection(
                        PatientContextCategory.PROBLEMS,
                        List.of(
                            new PatientContextCode("urn:synthetic:clinical", "SYN-PROBLEM-001")))),
                new PatientContextSourceEvidence(
                    "SYNTHETIC_IPS_SOURCE",
                    Optional.of("v1"),
                    Optional.of(now.minusSeconds(30)),
                    now,
                    "IPS_PROJECTION_V1",
                    PatientContextSourceEvidence.Freshness.CURRENT));
    return new ReferralPatientContextQueries(
        store,
        transferPort,
        authorization,
        auditWriter,
        Optional.of(policy),
        Optional.of(source),
        transactions(),
        Clock.fixed(now, java.time.ZoneOffset.UTC));
  }

  private static PlatformTransactionManager transactions() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }

  private static PatientReference patientReference() {
    return new PatientReference(
        new ExternalReference(
            new IssuerId("urn:synthetic:clinical-source"), "Patient", PATIENT_MARKER),
        Optional.empty());
  }

  private static ExternalReference directoryReference(String type, String value) {
    return new ExternalReference(new IssuerId("urn:ayuvahak:directory"), type, value);
  }

  private static void setAuthenticatedPrincipal() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR, "synthetic-token"));
  }
}
