package org.ayuvahak.referral.adapter.ips;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.fhir.FhirBoundaryException;
import org.ayuvahak.platform.fhir.FhirBoundaryFailure;
import org.ayuvahak.platform.fhir.FhirPayloadLimits;
import org.ayuvahak.platform.fhir.FhirProfileContext;
import org.ayuvahak.platform.fhir.FhirR4PayloadParser;
import org.ayuvahak.referral.application.PatientContextCategory;
import org.ayuvahak.referral.application.PatientContextSourceDocument;
import org.ayuvahak.referral.application.PatientContextSourceEvidence;
import org.ayuvahak.referral.domain.PatientReference;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;

class ReferralIpsPatientContextMapperTest {

  private static final String IPS_PATIENT_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips|2.0.1";
  private static final String IPS_COMPOSITION_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips|2.0.1";
  private static final String IPS_BUNDLE_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips|2.0.1";
  private static final String IPS_CONDITION_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Condition-uv-ips|2.0.1";
  static final String PATIENT_IDENTIFIER_SYSTEM = "urn:synthetic:clinical-source:patients";
  static final String PATIENT_IDENTIFIER = "SYN-PRIVATE-PATIENT-MARKER-8F93";
  private static final String PRIVATE_NARRATIVE = "SYN-PRIVATE-NARRATIVE-CONTENT";
  private static final String PRIVATE_DISPLAY = "SYN-PRIVATE-DISPLAY-CONTENT";
  static final String PROBLEM_CODE = "SYN-CODED-PROBLEM-001";
  private static final String CONDITION_URL = "urn:uuid:00000000-0000-4000-8000-000000000003";
  private static final String PATIENT_URL = "urn:uuid:00000000-0000-4000-8000-000000000002";

  // Semantic mapper tests use a generous but still bounded budget; production defaults stay
  // unchanged.
  private static final FhirPayloadLimits TEST_PAYLOAD_LIMITS =
      new FhirPayloadLimits(4 * 1024 * 1024, 64, 250, 1000, Duration.ofSeconds(8));

  private final ReferralIpsPatientContextMapper mapper =
      new ReferralIpsPatientContextMapper(
          FhirR4PayloadParser.withCoreProfiles(TEST_PAYLOAD_LIMITS));

  @Test
  void validatesIpsBundleAndProjectsOnlyAllowedSourceScopedCodes() {
    PatientReference expected = patientReference(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER);
    PatientContextSourceDocument result =
        mapper.map(
            bytes(ipsBundle(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER)),
            "application/fhir+json",
            expected,
            Set.of(PatientContextCategory.PROBLEMS),
            sourceEvidence());

    assertThat(result.status()).isEqualTo(PatientContextSourceDocument.Status.AVAILABLE);
    assertThat(result.sections())
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.category()).isEqualTo(PatientContextCategory.PROBLEMS);
              assertThat(section.codes()).extracting("code").containsExactly(PROBLEM_CODE);
              assertThat(section.codes().getFirst().system()).isEqualTo("urn:synthetic:clinical");
            });
    assertThat(result.toString())
        .doesNotContain(PATIENT_IDENTIFIER, PRIVATE_NARRATIVE, PRIVATE_DISPLAY, PROBLEM_CODE);
  }

  @Test
  void syntheticIpsBundleAndItsNestedProfilesPassTheirPinnedProfiles() {
    FhirR4PayloadParser parser = FhirR4PayloadParser.withCoreProfiles(TEST_PAYLOAD_LIMITS);
    Bundle bundle =
        FhirContext.forR4Cached()
            .newJsonParser()
            .parseResource(Bundle.class, ipsBundle(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER));

    FhirBoundaryFailure bundleFailure =
        failure(
            () ->
                parser.parse(
                    bytes(ipsBundle(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER)),
                    "application/fhir+json",
                    new FhirProfileContext("4.0.1", IPS_BUNDLE_PROFILE)));
    FhirBoundaryFailure compositionFailure =
        failure(
            () ->
                parser.parse(
                    bytes(
                        FhirContext.forR4Cached()
                            .newJsonParser()
                            .encodeResourceToString(bundle.getEntryFirstRep().getResource())),
                    "application/fhir+json",
                    new FhirProfileContext("4.0.1", IPS_COMPOSITION_PROFILE)));
    FhirBoundaryFailure patientFailure =
        failure(
            () ->
                parser.parse(
                    bytes(
                        FhirContext.forR4Cached()
                            .newJsonParser()
                            .encodeResourceToString(bundle.getEntry().get(1).getResource())),
                    "application/fhir+json",
                    new FhirProfileContext("4.0.1", IPS_PATIENT_PROFILE)));
    FhirBoundaryFailure conditionFailure =
        failure(
            () ->
                parser.parse(
                    bytes(
                        FhirContext.forR4Cached()
                            .newJsonParser()
                            .encodeResourceToString(bundle.getEntry().get(2).getResource())),
                    "application/fhir+json",
                    new FhirProfileContext("4.0.1", IPS_CONDITION_PROFILE)));
    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(bundleFailure).as("Bundle profile failure category").isNull();
    softly.assertThat(compositionFailure).as("Composition profile failure category").isNull();
    softly.assertThat(patientFailure).as("Patient profile failure category").isNull();
    softly.assertThat(conditionFailure).as("Condition profile failure category").isNull();
    softly.assertAll();
  }

  @Test
  void rejectsPatientWhenIdentifierSystemDiffersEvenIfValueMatches() {
    PatientReference expected = patientReference(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER);

    PatientContextSourceDocument result =
        mapper.map(
            bytes(ipsBundle("urn:synthetic:other-source", PATIENT_IDENTIFIER)),
            "application/fhir+json",
            expected,
            Set.of(PatientContextCategory.PROBLEMS),
            sourceEvidence());

    assertThat(result.status()).isEqualTo(PatientContextSourceDocument.Status.INVALID);
    assertThat(result.sections()).isEmpty();
  }

  @Test
  void rejectsPatientWhenIdentifierValueDiffersEvenIfSystemMatches() {
    PatientReference expected = patientReference(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER);

    PatientContextSourceDocument result =
        mapper.map(
            bytes(ipsBundle(PATIENT_IDENTIFIER_SYSTEM, "SYN-PATIENT-OTHER")),
            "application/fhir+json",
            expected,
            Set.of(PatientContextCategory.PROBLEMS),
            sourceEvidence());

    assertThat(result.status()).isEqualTo(PatientContextSourceDocument.Status.INVALID);
    assertThat(result.sections()).isEmpty();
  }

  @Test
  void doesNotResolvePatientByFhirIdOrBundleFullUrlWhenNamespacedIdentifierIsMissing() {
    PatientReference expected = patientReference(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER);

    PatientContextSourceDocument result =
        mapper.map(
            bytes(ipsBundle(null, null)),
            "application/fhir+json",
            expected,
            Set.of(PatientContextCategory.PROBLEMS),
            sourceEvidence());

    assertThat(result.status()).isEqualTo(PatientContextSourceDocument.Status.INVALID);
    assertThat(result.sections()).isEmpty();
  }

  @Test
  void rejectsProjectedConditionWhoseSubjectIsNotTheIdentifiedPatientFullUrl() {
    PatientReference expected = patientReference(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER);

    PatientContextSourceDocument result =
        mapper.map(
            bytes(
                ipsBundle(
                    PATIENT_IDENTIFIER_SYSTEM,
                    PATIENT_IDENTIFIER,
                    "https://external.invalid/Patient/SYN-PATIENT-OTHER")),
            "application/fhir+json",
            expected,
            Set.of(PatientContextCategory.PROBLEMS),
            sourceEvidence());

    assertThat(result.status()).isEqualTo(PatientContextSourceDocument.Status.INVALID);
    assertThat(result.sections()).isEmpty();
  }

  @Test
  void rejectsUnreferencedClinicalResourcesInTheIpsBundle() {
    PatientReference expected = patientReference(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER);
    Bundle bundle =
        FhirContext.forR4Cached()
            .newJsonParser()
            .parseResource(Bundle.class, ipsBundle(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER));
    Condition unreferenced = new Condition();
    unreferenced.setId("00000000-0000-4000-8000-000000000004");
    unreferenced.getMeta().addProfile(IPS_CONDITION_PROFILE);
    unreferenced.setSubject(new Reference(PATIENT_URL));
    unreferenced.setCode(
        new CodeableConcept()
            .addCoding(new Coding("urn:synthetic:clinical", "SYN-ORPHAN-CODE", "orphan")));
    bundle
        .addEntry()
        .setFullUrl("urn:uuid:00000000-0000-4000-8000-000000000004")
        .setResource(unreferenced);

    PatientContextSourceDocument result =
        mapper.map(
            bytes(FhirContext.forR4Cached().newJsonParser().encodeResourceToString(bundle)),
            "application/fhir+json",
            expected,
            Set.of(PatientContextCategory.PROBLEMS),
            sourceEvidence());

    assertThat(result.status()).isEqualTo(PatientContextSourceDocument.Status.INVALID);
    assertThat(result.sections()).isEmpty();
  }

  @Test
  void doesNotProjectCategoriesThatDisclosurePolicyDidNotAllow() {
    PatientReference expected = patientReference(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER);

    PatientContextSourceDocument result =
        mapper.map(
            bytes(ipsBundle(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER)),
            "application/fhir+json",
            expected,
            Set.of(PatientContextCategory.ALLERGIES),
            sourceEvidence());

    assertThat(result.status()).isEqualTo(PatientContextSourceDocument.Status.AVAILABLE);
    assertThat(result.sections()).isEmpty();
  }

  @Test
  void invalidOrMalformedIpsBundleReturnsNoClinicalProjection() {
    PatientReference expected = patientReference(PATIENT_IDENTIFIER_SYSTEM, PATIENT_IDENTIFIER);

    PatientContextSourceDocument result =
        mapper.map(
            bytes("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}"),
            "application/fhir+json",
            expected,
            Set.of(PatientContextCategory.PROBLEMS),
            sourceEvidence());

    assertThat(result.status()).isEqualTo(PatientContextSourceDocument.Status.INVALID);
    assertThat(result.sections()).isEmpty();
  }

  static PatientReference patientReference(String system, String value) {
    return new PatientReference(
        new ExternalReference(new IssuerId(system), "Patient", value), Optional.empty());
  }

  static PatientContextSourceEvidence sourceEvidence() {
    Instant receivedAt = Instant.parse("2026-10-15T08:00:00Z");
    return new PatientContextSourceEvidence(
        "SYNTHETIC_IPS_SOURCE",
        Optional.of("v1"),
        Optional.of(receivedAt.minusSeconds(30)),
        receivedAt,
        "IPS_PROJECTION_V1",
        PatientContextSourceEvidence.Freshness.CURRENT);
  }

  static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  static String ipsBundle(String patientSystem, String patientIdentifier) {
    return ipsBundle(patientSystem, patientIdentifier, PATIENT_URL);
  }

  private static String ipsBundle(
      String patientSystem, String patientIdentifier, String conditionSubjectReference) {
    Bundle bundle = new Bundle();
    bundle.getMeta().addProfile(IPS_BUNDLE_PROFILE);
    bundle.setIdentifier(
        new Identifier().setSystem("urn:synthetic:ips-documents").setValue("SYN-IPS-001"));
    bundle.setType(Bundle.BundleType.DOCUMENT);
    bundle.setTimestamp(Date.from(Instant.parse("2026-10-15T08:00:00Z")));

    Patient patient = new Patient();
    patient.setId("00000000-0000-4000-8000-000000000002");
    patient.getMeta().addProfile(IPS_PATIENT_PROFILE);
    if (patientSystem != null && patientIdentifier != null) {
      patient.addIdentifier(new Identifier().setSystem(patientSystem).setValue(patientIdentifier));
    }
    patient.addName().setText("SYNTHETIC PERSON — NOT REAL");
    patient.setBirthDate(Date.from(Instant.parse("2000-01-01T00:00:00Z")));

    Condition condition = new Condition();
    condition.setId("00000000-0000-4000-8000-000000000003");
    condition.getMeta().addProfile(IPS_CONDITION_PROFILE);
    condition.setSubject(new Reference(conditionSubjectReference));
    condition.setCode(
        new CodeableConcept()
            .addCoding(new Coding("urn:synthetic:clinical", PROBLEM_CODE, PRIVATE_DISPLAY)));

    Composition composition = new Composition();
    composition.getMeta().addProfile(IPS_COMPOSITION_PROFILE);
    composition.setStatus(Composition.CompositionStatus.FINAL);
    composition.setType(
        new CodeableConcept()
            .addCoding(new Coding("http://loinc.org", "60591-5", "Patient summary")));
    composition
        .addEvent()
        .addCode(
            new CodeableConcept()
                .addCoding(
                    new Coding(
                        "http://terminology.hl7.org/CodeSystem/v3-ActClass",
                        "PCPR",
                        "care provision")));
    composition.setSubject(new Reference(PATIENT_URL));
    composition.setDate(Date.from(Instant.parse("2026-10-15T08:00:00Z")));
    composition.addAuthor(new Reference().setDisplay("Synthetic IPS author"));
    composition.setTitle("Synthetic patient summary");
    composition
        .addSection()
        .setTitle("Problems")
        .setCode(
            new CodeableConcept()
                .addCoding(new Coding("http://loinc.org", "11450-4", "Problem list")))
        .setText(narrative(PRIVATE_NARRATIVE))
        .addEntry(new Reference(CONDITION_URL));
    composition
        .addSection()
        .setTitle("Allergies and intolerances")
        .setCode(
            new CodeableConcept().addCoding(new Coding("http://loinc.org", "48765-2", "Allergies")))
        .setText(narrative("No synthetic allergy records."))
        .setEmptyReason(
            new CodeableConcept()
                .addCoding(
                    new Coding(
                        "http://terminology.hl7.org/CodeSystem/list-empty-reason",
                        "unavailable",
                        "Unavailable")));
    composition
        .addSection()
        .setTitle("Medication summary")
        .setCode(
            new CodeableConcept()
                .addCoding(new Coding("http://loinc.org", "10160-0", "Medication summary")))
        .setText(narrative("No synthetic medication records."))
        .setEmptyReason(
            new CodeableConcept()
                .addCoding(
                    new Coding(
                        "http://terminology.hl7.org/CodeSystem/list-empty-reason",
                        "unavailable",
                        "Unavailable")));

    bundle
        .addEntry()
        .setFullUrl("urn:uuid:00000000-0000-4000-8000-000000000001")
        .setResource(composition);
    bundle.addEntry().setFullUrl(PATIENT_URL).setResource(patient);
    bundle.addEntry().setFullUrl(CONDITION_URL).setResource(condition);
    return FhirContext.forR4Cached().newJsonParser().encodeResourceToString(bundle);
  }

  private static FhirBoundaryFailure failure(org.junit.jupiter.api.function.Executable operation) {
    try {
      operation.execute();
      return null;
    } catch (FhirBoundaryException rejected) {
      return rejected.failure();
    } catch (Throwable unexpected) {
      throw new AssertionError("synthetic FHIR validation probe failed unexpectedly", unexpected);
    }
  }

  private static Narrative narrative(String syntheticText) {
    Narrative narrative = new Narrative();
    narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
    narrative.setDivAsString(
        "<div xmlns=\"http://www.w3.org/1999/xhtml\">" + syntheticText + "</div>");
    return narrative;
  }
}
