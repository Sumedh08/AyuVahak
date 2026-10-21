package org.ayuvahak.platform.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationOptions;
import java.util.concurrent.Callable;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StructureDefinition;

/** Initializes local core and guide profile validation before request processing begins. */
final class FhirProfileStartupWarmup implements Callable<Void> {

  private static final String CORE_PATIENT_PROFILE =
      "http://hl7.org/fhir/StructureDefinition/Patient";

  private final FhirContext fhirContext;
  private final IValidationSupport validationSupport;
  private final FhirValidator validator;
  private final Object validatorLock;
  private final String mcsdOrganizationProfile;
  private final String ipsPatientProfile;
  private final String ipsCompositionProfile;
  private final String ipsBundleProfile;

  FhirProfileStartupWarmup(
      FhirContext fhirContext,
      IValidationSupport validationSupport,
      FhirValidator validator,
      Object validatorLock,
      String mcsdOrganizationProfile,
      String ipsPatientProfile,
      String ipsCompositionProfile,
      String ipsBundleProfile) {
    this.fhirContext = fhirContext;
    this.validationSupport = validationSupport;
    this.validator = validator;
    this.validatorLock = validatorLock;
    this.mcsdOrganizationProfile = mcsdOrganizationProfile;
    this.ipsPatientProfile = ipsPatientProfile;
    this.ipsCompositionProfile = ipsCompositionProfile;
    this.ipsBundleProfile = ipsBundleProfile;
  }

  @Override
  public Void call() {
    warmJsonParser();
    validate(new Patient(), CORE_PATIENT_PROFILE);
    validate(new Organization(), mcsdOrganizationProfile);
    validate(new Patient(), ipsPatientProfile);
    validate(ipsBundleWarmupResource(), ipsBundleProfile);
    return null;
  }

  private void validate(IBaseResource resource, String profile) {
    requireProfile(profile);
    ValidationOptions options = new ValidationOptions().addProfile(profile);
    synchronized (validatorLock) {
      // Results are intentionally ignored: these incomplete synthetic resources exercise local
      // profile initialization. Actual conformance is enforced on each parsed request.
      validator.validateWithResult(resource, options);
    }
  }

  private void warmJsonParser() {
    try {
      IParser parser = fhirContext.newJsonParser();
      parser.setParserErrorHandler(new StrictErrorHandler());
      parser.parseResource(
          """
          {"resourceType":"Bundle","type":"searchset","timestamp":"2000-01-01T00:00:00Z","entry":[{"fullUrl":"https://synthetic.example.test/fhir/Organization/SYN-ORG-WARMUP","resource":{"resourceType":"Organization","id":"SYN-ORG-WARMUP","meta":{"profile":["https://profiles.ihe.net/ITI/mCSD/StructureDefinition/IHE.mCSD.Organization|4.0.0"],"versionId":"1","lastUpdated":"2000-01-01T00:00:00Z"},"active":true,"name":"Synthetic Directory Warm-up"}}]}
          """);
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "Synthetic FHIR JSON parser warm-up resource was not parseable", exception);
    }
  }

  private void requireProfile(String profile) {
    int versionSeparator = profile.lastIndexOf('|');
    String url = versionSeparator < 0 ? profile : profile.substring(0, versionSeparator);
    String requestedVersion = versionSeparator < 0 ? null : profile.substring(versionSeparator + 1);
    IBaseResource resource = validationSupport.fetchStructureDefinition(url);
    if (!(resource instanceof StructureDefinition definition)
        || (requestedVersion != null && !requestedVersion.equals(definition.getVersion()))) {
      throw new IllegalStateException("Pinned local FHIR profile is unavailable: " + profile);
    }
  }

  private Bundle ipsBundleWarmupResource() {
    Bundle bundle = new Bundle();
    bundle.getMeta().addProfile(ipsBundleProfile);
    bundle.setType(Bundle.BundleType.DOCUMENT);
    bundle.setIdentifier(
        new Identifier()
            .setSystem("urn:ayuvahak:synthetic-profile-warmup")
            .setValue("SYN-WARMUP-001"));
    bundle.setTimestamp(java.util.Date.from(java.time.Instant.EPOCH));

    Patient patient = new Patient();
    patient.getMeta().addProfile(ipsPatientProfile);
    String patientReference = "urn:uuid:00000000-0000-4000-8000-000000000002";

    Composition composition = new Composition();
    composition.getMeta().addProfile(ipsCompositionProfile);
    composition.addAuthor(new Reference().setDisplay("Synthetic profile warm-up author"));
    composition.setSubject(new Reference(patientReference));

    bundle
        .addEntry()
        .setFullUrl("urn:uuid:00000000-0000-4000-8000-000000000001")
        .setResource(composition);
    bundle.addEntry().setFullUrl(patientReference).setResource(patient);
    return bundle;
  }
}
