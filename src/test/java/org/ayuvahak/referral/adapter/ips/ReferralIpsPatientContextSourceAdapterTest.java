package org.ayuvahak.referral.adapter.ips;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.fhir.FhirPayloadLimits;
import org.ayuvahak.platform.fhir.FhirR4PayloadParser;
import org.ayuvahak.referral.application.PatientContextCategory;
import org.ayuvahak.referral.application.PatientContextSourceDocument;
import org.ayuvahak.referral.application.PatientContextSourceRequest;
import org.ayuvahak.referral.application.ReferralIpsPayload;
import org.ayuvahak.referral.application.ReferralIpsPayloadReadPort;
import org.ayuvahak.referral.domain.PatientReference;
import org.junit.jupiter.api.Test;

class ReferralIpsPatientContextSourceAdapterTest {

  @Test
  void readsOnlyForReferralRequestAndMapsSyntheticIpsToTransientAllowlistedProjection() {
    PatientReference expected =
        ReferralIpsPatientContextMapperTest.patientReference(
            ReferralIpsPatientContextMapperTest.PATIENT_IDENTIFIER_SYSTEM,
            ReferralIpsPatientContextMapperTest.PATIENT_IDENTIFIER);
    PatientContextSourceRequest request =
        new PatientContextSourceRequest(
            expected,
            "CARE_COORDINATION",
            Set.of(PatientContextCategory.PROBLEMS),
            CorrelationId.generate());
    ReferralIpsPayloadReadPort reader =
        actual -> {
          assertThat(actual.patientReference()).isEqualTo(expected);
          assertThat(actual.purposeCode()).isEqualTo("CARE_COORDINATION");
          assertThat(actual.permittedCategories()).containsExactly(PatientContextCategory.PROBLEMS);
          return new ReferralIpsPayload(
              ReferralIpsPatientContextMapperTest.bytes(
                  ReferralIpsPatientContextMapperTest.ipsBundle(
                      ReferralIpsPatientContextMapperTest.PATIENT_IDENTIFIER_SYSTEM,
                      ReferralIpsPatientContextMapperTest.PATIENT_IDENTIFIER)),
              "application/fhir+json",
              ReferralIpsPatientContextMapperTest.sourceEvidence());
        };
    ReferralIpsPatientContextSourceAdapter adapter =
        new ReferralIpsPatientContextSourceAdapter(
            new ReferralIpsPatientContextMapper(
                FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults())),
            reader);

    var result = adapter.retrieve(request);

    assertThat(result.status()).isEqualTo(PatientContextSourceDocument.Status.AVAILABLE);
    assertThat(result.sections())
        .singleElement()
        .satisfies(
            section ->
                assertThat(section.codes())
                    .extracting("code")
                    .containsExactly(ReferralIpsPatientContextMapperTest.PROBLEM_CODE));
    assertThat(result.toString())
        .doesNotContain(
            ReferralIpsPatientContextMapperTest.PATIENT_IDENTIFIER,
            "SYN-PRIVATE-NARRATIVE-CONTENT",
            ReferralIpsPatientContextMapperTest.PROBLEM_CODE);
  }
}
