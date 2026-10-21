package org.ayuvahak.platform.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;

class FhirR4PayloadParserTest {

  private static final String PATIENT_PROFILE = "http://hl7.org/fhir/StructureDefinition/Patient";
  private static final FhirProfileContext PATIENT_CONTEXT =
      new FhirProfileContext("4.0.1", PATIENT_PROFILE);
  private static final String MCSD_ORGANIZATION_PROFILE =
      "https://profiles.ihe.net/ITI/mCSD/StructureDefinition/IHE.mCSD.Organization|4.0.0";
  private static final String IPS_PATIENT_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips|2.0.1";
  private static final String IPS_BUNDLE_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips|2.0.1";

  @Test
  void parsesAndValidatesAnR4PatientAgainstAnExplicitLocalProfile() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());
    byte[] payload =
        """
        {"resourceType":"Patient","active":true}
        """
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    FhirR4ParsedResource parsed = parser.parse(payload, "application/fhir+json", PATIENT_CONTEXT);

    assertThat(parsed.fhirRelease()).isEqualTo("4.0.1");
    assertThat(parsed.profileUri()).isEqualTo(PATIENT_PROFILE);
    assertThat(parsed.resource()).isInstanceOf(Patient.class);
    assertThat(((Patient) parsed.resource()).getActive()).isTrue();
  }

  @Test
  void validatesThePinnedMcsdOrganizationProfileAndRejectsItsMissingRequiredType() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());
    String validOrganization =
        """
        {"resourceType":"Organization","meta":{"profile":["%s"]},
         "identifier":[{"system":"urn:ayuvahak:synthetic-orgs","value":"SYN-ORG-001"}],
         "active":true,"type":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/organization-type","code":"dept","display":"Department"}]}],
         "name":"Synthetic Organization"}
        """
            .formatted(MCSD_ORGANIZATION_PROFILE);
    FhirProfileContext context = new FhirProfileContext("4.0.1", MCSD_ORGANIZATION_PROFILE);

    FhirR4ParsedResource parsed =
        parser.parse(bytes(validOrganization), "application/fhir+json", context);

    assertThat(parsed.profileUri()).isEqualTo(MCSD_ORGANIZATION_PROFILE);
    assertThat(parsed.resource()).isInstanceOf(org.hl7.fhir.r4.model.Organization.class);
    assertThat(parsed.terminologyAssessment())
        .isEqualTo(FhirTerminologyAssessment.LOCAL_TERMINOLOGY_COVERAGE_INDETERMINATE);
    String missingRequiredType =
        """
        {"resourceType":"Organization","meta":{"profile":["%s"]},
         "name":"Synthetic Organization"}
        """
            .formatted(MCSD_ORGANIZATION_PROFILE);
    assertThatThrownBy(
            () -> parser.parse(bytes(missingRequiredType), "application/fhir+json", context))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.PROFILE_VALIDATION_FAILED);
  }

  @Test
  void validatesThePinnedIpsPatientProfileAndRejectsItsMissingRequiredName() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(
            new FhirPayloadLimits(4 * 1024 * 1024, 64, 250, 1000, Duration.ofSeconds(8)));
    String validPatient =
        """
        {"resourceType":"Patient","meta":{"profile":["%s"]},
         "identifier":[{"system":"urn:ayuvahak:synthetic-patients","value":"SYN-PATIENT-001"}],
         "birthDate":"2000-01-01",
         "name":[{"text":"Synthetic Person"}]}
        """
            .formatted(IPS_PATIENT_PROFILE);
    FhirProfileContext context = new FhirProfileContext("4.0.1", IPS_PATIENT_PROFILE);

    FhirR4ParsedResource parsed =
        parser.parse(bytes(validPatient), "application/fhir+json", context);

    assertThat(parsed.profileUri()).isEqualTo(IPS_PATIENT_PROFILE);
    assertThat(parsed.resource()).isInstanceOf(Patient.class);
    assertThat(parsed.terminologyAssessment())
        .isEqualTo(FhirTerminologyAssessment.LOCAL_TERMINOLOGY_COVERAGE_INDETERMINATE);
    String missingRequiredName =
        """
        {"resourceType":"Patient","meta":{"profile":["%s"]},
         "identifier":[{"system":"urn:ayuvahak:synthetic-patients","value":"SYN-PATIENT-002"}]}
        """
            .formatted(IPS_PATIENT_PROFILE);
    assertThatThrownBy(
            () -> parser.parse(bytes(missingRequiredName), "application/fhir+json", context))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.PROFILE_VALIDATION_FAILED);
  }

  @Test
  void coldStartupValidatesThePinnedIpsBundleProfileAndRejectsANonDocumentBundle() {
    long startupStartedAt = System.nanoTime();
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(
            new FhirPayloadLimits(4 * 1024 * 1024, 64, 250, 1000, Duration.ofSeconds(8)));
    Duration startupElapsed = Duration.ofNanos(System.nanoTime() - startupStartedAt);
    assertThat(startupElapsed).isLessThan(Duration.ofSeconds(30));

    FhirProfileContext context = new FhirProfileContext("4.0.1", IPS_BUNDLE_PROFILE);
    String validDocument = ipsBundle(Bundle.BundleType.DOCUMENT);

    FhirR4ParsedResource parsed =
        parser.parse(bytes(validDocument), "application/fhir+json", context);

    assertThat(parsed.profileUri()).isEqualTo(IPS_BUNDLE_PROFILE);
    assertThat(parsed.resource()).isInstanceOf(org.hl7.fhir.r4.model.Bundle.class);
    assertThat(parsed.terminologyAssessment())
        .isEqualTo(FhirTerminologyAssessment.LOCAL_TERMINOLOGY_COVERAGE_INDETERMINATE);
    String nonDocumentBundle = ipsBundle(Bundle.BundleType.COLLECTION);
    assertThatThrownBy(
            () -> parser.parse(bytes(nonDocumentBundle), "application/fhir+json", context))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.PROFILE_VALIDATION_FAILED);
  }

  @Test
  void rejectsMultipleTopLevelResourcesInsteadOfSilentlyParsingTheFirst() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());
    byte[] payload =
        bytes(
            "{\"resourceType\":\"Patient\",\"active\":true} {\"resourceType\":\"Patient\",\"active\":false}");

    assertThatThrownBy(() -> parser.parse(payload, "application/fhir+json", PATIENT_CONTEXT))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.MALFORMED_JSON);
  }

  @Test
  void rejectsNonObjectRoots() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());

    for (String payload : List.of("[]", "false", "[ {\"resourceType\":\"Patient\"} ]")) {
      assertThatThrownBy(
              () -> parser.parse(bytes(payload), "application/fhir+json", PATIENT_CONTEXT))
          .as("FHIR resource root %s", payload)
          .isInstanceOf(FhirBoundaryException.class)
          .extracting(exception -> ((FhirBoundaryException) exception).failure())
          .isEqualTo(FhirBoundaryFailure.MALFORMED_JSON);
    }
  }

  @Test
  void rejectsDuplicateResourceTypeProperties() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());
    byte[] payload = bytes("{\"resourceType\":\"Patient\",\"resourceType\":\"Patient\"}");

    assertThatThrownBy(() -> parser.parse(payload, "application/fhir+json", PATIENT_CONTEXT))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.MALFORMED_JSON);
  }

  @Test
  void capsBundleEntriesEvenWhenEntriesDoNotContainResources() {
    FhirPayloadLimits limits = new FhirPayloadLimits(4096, 16, 8, 1, Duration.ofSeconds(10));
    FhirR4PayloadParser parser = FhirR4PayloadParser.withCoreProfiles(limits);
    String bundle =
        """
        {"resourceType":"Bundle","type":"collection","entry":[{},{}]}
        """;

    assertThatThrownBy(
            () ->
                parser.parse(
                    bytes(bundle),
                    "application/fhir+json",
                    new FhirProfileContext(
                        "4.0.1", "http://hl7.org/fhir/StructureDefinition/Bundle")))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.LIMIT_EXCEEDED);
  }

  @Test
  void rejectsDeclaredProfilesOutsideTheConfiguredProfileSet() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());
    byte[] payload =
        bytes(
            "{\"resourceType\":\"Patient\",\"meta\":{\"profile\":[\"http://hl7.org/fhir/StructureDefinition/Organization\"]}}");

    assertThatThrownBy(() -> parser.parse(payload, "application/fhir+json", PATIENT_CONTEXT))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.UNSUPPORTED_PROFILE);
  }

  @Test
  void acceptsDeclaredProfileOnlyWhenItIsExplicitlyAllowlistedAndAvailableLocally() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());
    byte[] payload =
        bytes(
            "{\"resourceType\":\"Patient\",\"active\":true,\"meta\":{\"profile\":[\""
                + PATIENT_PROFILE
                + "\"]}}");
    FhirProfileContext context =
        new FhirProfileContext("4.0.1", PATIENT_PROFILE, Set.of(PATIENT_PROFILE));

    FhirR4ParsedResource parsed = parser.parse(payload, "application/fhir+json", context);

    assertThat(parsed.resource()).isInstanceOf(Patient.class);
  }

  @Test
  void concurrentProfileValidationsReturnTheSameValidatedResourceSemantics() throws Exception {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(
            new FhirPayloadLimits(4096, 16, 8, 8, Duration.ofSeconds(10)));
    int workerCount = 8;
    int requestsPerWorker = 2;
    ExecutorService executor = Executors.newFixedThreadPool(workerCount);
    CountDownLatch ready = new CountDownLatch(workerCount);
    CountDownLatch start = new CountDownLatch(1);
    byte[] payload = bytes("{\"resourceType\":\"Patient\",\"active\":true}");
    List<Future<List<Boolean>>> results = new ArrayList<>();

    try {
      for (int worker = 0; worker < workerCount; worker++) {
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                  List<Boolean> activeValues = new ArrayList<>();
                  // Queue saturation and overload behavior have separate focused acceptance tests.
                  for (int request = 0; request < requestsPerWorker; request++) {
                    FhirR4ParsedResource parsed =
                        parser.parse(payload, "application/fhir+json", PATIENT_CONTEXT);
                    assertThat(parsed.resource()).isInstanceOf(Patient.class);
                    activeValues.add(((Patient) parsed.resource()).getActive());
                  }
                  return activeValues;
                }));
      }

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      for (Future<List<Boolean>> result : results) {
        assertThat(result.get(120, TimeUnit.SECONDS)).containsOnly(true);
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void mapsQueuedValidationTimeoutToATransientFailureAndRemovesCancelledWork() throws Exception {
    try (BoundedFhirValidationExecutor validationExecutor = new BoundedFhirValidationExecutor(1)) {
      FhirR4PayloadParser parser =
          new FhirR4PayloadParser(
              new FhirPayloadLimits(4096, 16, 8, 8, Duration.ofMillis(500)), validationExecutor);
      ExecutorService caller = Executors.newSingleThreadExecutor();
      CountDownLatch validationBlockStarted = new CountDownLatch(1);
      CountDownLatch releaseValidationBlock = new CountDownLatch(1);
      Future<String> blockedValidation =
          caller.submit(
              () ->
                  validationExecutor.execute(
                      () -> {
                        validationBlockStarted.countDown();
                        releaseValidationBlock.await();
                        return "released";
                      },
                      Duration.ofSeconds(5)));

      try {
        assertThat(validationBlockStarted.await(5, TimeUnit.SECONDS)).isTrue();
        byte[] payload = bytes("{\"resourceType\":\"Patient\",\"active\":true}");

        assertThatThrownBy(() -> parser.parse(payload, "application/fhir+json", PATIENT_CONTEXT))
            .isInstanceOf(FhirBoundaryException.class)
            .extracting(exception -> ((FhirBoundaryException) exception).failure())
            .isEqualTo(FhirBoundaryFailure.VALIDATION_TIMED_OUT);
        assertThat(validationExecutor.activeTaskCount()).isLessThanOrEqualTo(1);
        assertThat(validationExecutor.queuedTaskCount()).isZero();
      } finally {
        releaseValidationBlock.countDown();
        assertThat(blockedValidation.get(2, TimeUnit.SECONDS)).isEqualTo("released");
        caller.shutdownNow();
        assertThat(caller.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void reportsAnExplicitOverloadFailureWhenTheBoundedValidationQueueIsFull() throws Exception {
    try (BoundedFhirValidationExecutor validationExecutor = new BoundedFhirValidationExecutor(1)) {
      FhirR4PayloadParser parser =
          new FhirR4PayloadParser(
              new FhirPayloadLimits(4096, 16, 8, 8, Duration.ofSeconds(2)), validationExecutor);
      ExecutorService callers = Executors.newFixedThreadPool(2);
      CountDownLatch runningValidationStarted = new CountDownLatch(1);
      CountDownLatch releaseRunningValidation = new CountDownLatch(1);
      Future<String> runningValidation =
          callers.submit(
              () ->
                  validationExecutor.execute(
                      () -> {
                        runningValidationStarted.countDown();
                        releaseRunningValidation.await();
                        return "running";
                      },
                      Duration.ofSeconds(5)));
      Future<String> queuedValidation = null;

      try {
        assertThat(runningValidationStarted.await(1, TimeUnit.SECONDS)).isTrue();
        queuedValidation =
            callers.submit(() -> validationExecutor.execute(() -> "queued", Duration.ofSeconds(5)));
        long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (validationExecutor.queuedTaskCount() == 0 && System.nanoTime() < queueDeadline) {
          Thread.yield();
        }
        assertThat(validationExecutor.queuedTaskCount()).isEqualTo(1);

        assertThatThrownBy(
                () ->
                    parser.parse(
                        bytes("{\"resourceType\":\"Patient\",\"active\":true}"),
                        "application/fhir+json",
                        PATIENT_CONTEXT))
            .isInstanceOf(FhirBoundaryException.class)
            .extracting(exception -> ((FhirBoundaryException) exception).failure())
            .isEqualTo(FhirBoundaryFailure.VALIDATION_OVERLOADED);

        releaseRunningValidation.countDown();
        assertThat(runningValidation.get(2, TimeUnit.SECONDS)).isEqualTo("running");
        assertThat(queuedValidation.get(2, TimeUnit.SECONDS)).isEqualTo("queued");
      } finally {
        releaseRunningValidation.countDown();
        callers.shutdownNow();
        assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void rejectsR4BOrR5InsteadOfGuessingFromPayloadShape() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());

    assertThatThrownBy(
            () ->
                parser.parse(
                    bytes("{\"resourceType\":\"Patient\"}"),
                    "application/fhir+json",
                    new FhirProfileContext("5.0.0", PATIENT_PROFILE)))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.UNSUPPORTED_FHIR_RELEASE);
  }

  @Test
  void rejectsUnsupportedProfileWithoutAttemptingRemoteResolution() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());

    assertThatThrownBy(
            () ->
                parser.parse(
                    bytes("{\"resourceType\":\"Patient\"}"),
                    "application/fhir+json",
                    new FhirProfileContext("4.0.1", "https://profiles.invalid/synthetic")))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.UNSUPPORTED_PROFILE);
  }

  @Test
  void rejectsNonFhirJsonMediaTypes() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());

    assertThatThrownBy(
            () ->
                parser.parse(
                    bytes("{\"resourceType\":\"Patient\"}"), "application/json", PATIENT_CONTEXT))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.UNSUPPORTED_MEDIA_TYPE);
  }

  @Test
  void rejectsMalformedJsonWithASanitizedFailure() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());

    assertThatThrownBy(
            () ->
                parser.parse(
                    bytes("{\"resourceType\":\"Patient\","),
                    "application/fhir+json",
                    PATIENT_CONTEXT))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.MALFORMED_JSON);
  }

  @Test
  void rejectsPayloadLargerThanConfiguredByteLimitBeforeParsing() {
    FhirPayloadLimits limits = new FhirPayloadLimits(24, 16, 8, Duration.ofSeconds(1));
    FhirR4PayloadParser parser = FhirR4PayloadParser.withCoreProfiles(limits);

    assertThatThrownBy(
            () ->
                parser.parse(
                    bytes("{\"resourceType\":\"Patient\",\"active\":true}"),
                    "application/fhir+json",
                    PATIENT_CONTEXT))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.LIMIT_EXCEEDED);
  }

  @Test
  void rejectsExcessiveJsonNestingBeforeModelConstruction() {
    FhirPayloadLimits limits = new FhirPayloadLimits(4096, 5, 8, Duration.ofSeconds(1));
    FhirR4PayloadParser parser = FhirR4PayloadParser.withCoreProfiles(limits);
    String payload =
        "{\"resourceType\":\"Patient\",\"unused\":" + "[".repeat(6) + "0" + "]".repeat(6) + "}";

    assertThatThrownBy(() -> parser.parse(bytes(payload), "application/fhir+json", PATIENT_CONTEXT))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.LIMIT_EXCEEDED);
  }

  @Test
  void rejectsBundlesExceedingConfiguredResourceCount() {
    FhirPayloadLimits limits = new FhirPayloadLimits(4096, 16, 1, Duration.ofSeconds(1));
    FhirR4PayloadParser parser = FhirR4PayloadParser.withCoreProfiles(limits);
    String bundle =
        """
        {"resourceType":"Bundle","type":"collection","entry":[
          {"resource":{"resourceType":"Patient"}},
          {"resource":{"resourceType":"Organization"}}
        ]}
        """;

    assertThatThrownBy(
            () ->
                parser.parse(
                    bytes(bundle),
                    "application/fhir+json",
                    new FhirProfileContext(
                        "4.0.1", "http://hl7.org/fhir/StructureDefinition/Bundle")))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.LIMIT_EXCEEDED);
  }

  @Test
  void rejectsWhenTheConfiguredProcessingBudgetExpiresDuringStructuralScanning() {
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(
            new FhirPayloadLimits(4096, 16, 8, Duration.ofNanos(1)));

    assertThatThrownBy(
            () ->
                parser.parse(
                    bytes("{\"resourceType\":\"Patient\"}"),
                    "application/fhir+json",
                    PATIENT_CONTEXT))
        .isInstanceOf(FhirBoundaryException.class)
        .extracting(exception -> ((FhirBoundaryException) exception).failure())
        .isEqualTo(FhirBoundaryFailure.VALIDATION_TIMED_OUT);
  }

  @Test
  void absoluteReferencesRemainInertDuringParsing() {
    // This semantics check stays separate from the default processing-time budget under suite load.
    FhirR4PayloadParser parser =
        FhirR4PayloadParser.withCoreProfiles(
            new FhirPayloadLimits(4 * 1024 * 1024, 64, 250, 1000, Duration.ofSeconds(8)));
    byte[] payload =
        bytes(
            "{\"resourceType\":\"Patient\",\"managingOrganization\":{\"reference\":\"https://attacker.invalid/Organization/1\"}}");

    FhirR4ParsedResource parsed = parser.parse(payload, "application/fhir+json", PATIENT_CONTEXT);

    assertThat(parsed.resource()).isInstanceOf(Patient.class);
    assertThat(((Patient) parsed.resource()).getManagingOrganization().getReference())
        .isEqualTo("https://attacker.invalid/Organization/1");
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String ipsBundle(Bundle.BundleType bundleType) {
    Bundle bundle = new Bundle();
    bundle.getMeta().addProfile(IPS_BUNDLE_PROFILE);
    bundle.setIdentifier(
        new Identifier().setSystem("urn:ayuvahak:synthetic-ips").setValue("SYN-IPS-001"));
    bundle.setType(bundleType);
    bundle.setTimestamp(Date.from(Instant.parse("2026-10-15T00:00:00Z")));

    Composition composition = new Composition();
    composition
        .getMeta()
        .addProfile("http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips|2.0.1");
    composition.setStatus(Composition.CompositionStatus.FINAL);
    composition.setType(loinc("60591-5", "Patient summary Document"));
    composition.setSubject(new Reference("urn:uuid:00000000-0000-4000-8000-000000000002"));
    composition.setDate(Date.from(Instant.parse("2026-10-15T00:00:00Z")));
    composition.addAuthor(new Reference().setDisplay("Synthetic IPS document author"));
    composition.setTitle("Synthetic patient summary");
    composition
        .addSection()
        .setTitle("Problems")
        .setCode(loinc("11450-4", "Problem list - Reported"))
        .setText(narrative("Synthetic fixture; no problem details."))
        .setEmptyReason(unavailable());
    composition
        .addSection()
        .setTitle("Allergies and intolerances")
        .setCode(loinc("48765-2", "Allergies and adverse reactions Document"))
        .setText(narrative("Synthetic fixture; no allergy details."))
        .setEmptyReason(unavailable());
    composition
        .addSection()
        .setTitle("Medication summary")
        .setCode(loinc("10160-0", "History of Medication use Narrative"))
        .setText(narrative("Synthetic fixture; no medication details."))
        .setEmptyReason(unavailable());

    Patient patient = new Patient();
    patient.getMeta().addProfile(IPS_PATIENT_PROFILE);
    patient.addIdentifier(
        new Identifier().setSystem("urn:ayuvahak:synthetic-patients").setValue("SYN-PATIENT-001"));
    patient.setBirthDate(Date.from(Instant.parse("2000-01-01T00:00:00Z")));
    patient.addName().setText("Synthetic Person");

    bundle
        .addEntry()
        .setFullUrl("urn:uuid:00000000-0000-4000-8000-000000000001")
        .setResource(composition);
    bundle
        .addEntry()
        .setFullUrl("urn:uuid:00000000-0000-4000-8000-000000000002")
        .setResource(patient);
    return FhirContext.forR4Cached().newJsonParser().encodeResourceToString(bundle);
  }

  private static CodeableConcept loinc(String code, String display) {
    return new CodeableConcept().addCoding(new Coding("http://loinc.org", code, display));
  }

  private static CodeableConcept unavailable() {
    return new CodeableConcept()
        .addCoding(
            new Coding(
                "http://terminology.hl7.org/CodeSystem/list-empty-reason",
                "unavailable",
                "Unavailable"));
  }

  private static Narrative narrative(String text) {
    Narrative narrative = new Narrative();
    narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
    narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">" + text + "</div>");
    return narrative;
  }
}
