package org.ayuvahak.referral.adapter.ips;

import ca.uhn.fhir.context.FhirContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.ayuvahak.platform.fhir.FhirBoundaryException;
import org.ayuvahak.platform.fhir.FhirBoundaryFailure;
import org.ayuvahak.platform.fhir.FhirProfileContext;
import org.ayuvahak.platform.fhir.FhirR4ParsedResource;
import org.ayuvahak.platform.fhir.FhirR4PayloadParser;
import org.ayuvahak.referral.application.PatientContextCategory;
import org.ayuvahak.referral.application.PatientContextCode;
import org.ayuvahak.referral.application.PatientContextSection;
import org.ayuvahak.referral.application.PatientContextSourceDocument;
import org.ayuvahak.referral.application.PatientContextSourceEvidence;
import org.ayuvahak.referral.domain.PatientReference;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.MedicationAdministration;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;

/** Referral-owned IPS projection boundary; unsupported content is withheld by default. */
final class ReferralIpsPatientContextMapper {

  private static final String FHIR_RELEASE = "4.0.1";
  private static final String FHIR_JSON = "application/fhir+json";
  private static final String BUNDLE_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips|2.0.1";
  private static final String COMPOSITION_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips|2.0.1";
  private static final String PATIENT_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips|2.0.1";
  private static final Set<String> ALLOWED_PROFILES =
      Set.of(BUNDLE_PROFILE, COMPOSITION_PROFILE, PATIENT_PROFILE);
  private static final String LOINC = "http://loinc.org";
  private static final int MAX_CODES_PER_SECTION = 50;

  private final FhirR4PayloadParser parser;

  ReferralIpsPatientContextMapper(FhirR4PayloadParser parser) {
    this.parser = Objects.requireNonNull(parser, "FHIR parser");
  }

  PatientContextSourceDocument map(
      byte[] payload,
      String contentType,
      PatientReference expectedPatient,
      Set<PatientContextCategory> permittedCategories,
      PatientContextSourceEvidence evidence) {
    Objects.requireNonNull(payload, "FHIR payload");
    Objects.requireNonNull(contentType, "FHIR content type");
    Objects.requireNonNull(expectedPatient, "expected patient");
    Objects.requireNonNull(permittedCategories, "permitted categories");
    Objects.requireNonNull(evidence, "source evidence");
    if (evidence.freshness() == PatientContextSourceEvidence.Freshness.STALE) {
      return document(
          PatientContextSourceDocument.Status.STALE, expectedPatient, List.of(), evidence);
    }
    if (evidence.freshness() == PatientContextSourceEvidence.Freshness.UNKNOWN) {
      return document(
          PatientContextSourceDocument.Status.UNAVAILABLE, expectedPatient, List.of(), evidence);
    }

    try {
      FhirR4ParsedResource parsed =
          parser.parse(
              payload,
              contentType,
              new FhirProfileContext(FHIR_RELEASE, BUNDLE_PROFILE, ALLOWED_PROFILES));
      if (!(parsed.resource() instanceof Bundle bundle)
          || !declaresProfile(bundle, BUNDLE_PROFILE)
          || bundle.getType() != Bundle.BundleType.DOCUMENT) {
        return invalid(expectedPatient, evidence);
      }

      Map<String, Resource> resourcesByFullUrl = resourcesByFullUrl(bundle);
      List<Composition> compositions =
          resourcesByFullUrl.values().stream()
              .filter(Composition.class::isInstance)
              .map(Composition.class::cast)
              .toList();
      List<Patient> patients =
          resourcesByFullUrl.values().stream()
              .filter(Patient.class::isInstance)
              .map(Patient.class::cast)
              .toList();
      if (compositions.size() != 1 || patients.size() != 1) {
        return invalid(expectedPatient, evidence);
      }

      Composition composition = compositions.getFirst();
      Patient patient = patients.getFirst();
      String patientFullUrl = fullUrlOf(resourcesByFullUrl, patient);
      if (!declaresProfile(composition, COMPOSITION_PROFILE)
          || !declaresProfile(patient, PATIENT_PROFILE)
          || !hasExactPatientIdentifier(patient, expectedPatient)
          || !patientFullUrl.equals(composition.getSubject().getReference())
          || !allEntriesStayWithinSupportedSections(
              bundle, composition, resourcesByFullUrl, patientFullUrl)) {
        return invalid(expectedPatient, evidence);
      }
      validateNestedProfile(composition, COMPOSITION_PROFILE);
      validateNestedProfile(patient, PATIENT_PROFILE);

      List<PatientContextSection> sections =
          projectSections(composition, resourcesByFullUrl, patientFullUrl, permittedCategories);
      if (sections == null) {
        return invalid(expectedPatient, evidence);
      }
      return document(
          PatientContextSourceDocument.Status.AVAILABLE, expectedPatient, sections, evidence);
    } catch (FhirBoundaryException invalidPayload) {
      PatientContextSourceDocument.Status status =
          invalidPayload.failure() == FhirBoundaryFailure.VALIDATION_OVERLOADED
                  || invalidPayload.failure() == FhirBoundaryFailure.VALIDATION_TIMED_OUT
              ? PatientContextSourceDocument.Status.UNAVAILABLE
              : PatientContextSourceDocument.Status.INVALID;
      return document(status, expectedPatient, List.of(), evidence);
    } catch (RuntimeException invalidPayload) {
      return invalid(expectedPatient, evidence);
    }
  }

  private static Map<String, Resource> resourcesByFullUrl(Bundle bundle) {
    Map<String, Resource> result = new HashMap<>();
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      Resource resource = entry.getResource();
      if (resource == null) {
        throw new IllegalArgumentException("IPS document contains an empty entry");
      }
      String fullUrl = entry.getFullUrl();
      if (fullUrl == null || fullUrl.isBlank() || result.putIfAbsent(fullUrl, resource) != null) {
        throw new IllegalArgumentException("IPS document resource references are ambiguous");
      }
    }
    return Map.copyOf(result);
  }

  private static boolean allEntriesStayWithinSupportedSections(
      Bundle bundle,
      Composition composition,
      Map<String, Resource> resourcesByFullUrl,
      String patientFullUrl) {
    if (bundle.hasLink()) {
      return false;
    }
    Set<String> referencedResources = new java.util.HashSet<>();
    for (Composition.SectionComponent section : composition.getSection()) {
      PatientContextCategory category = category(section.getCode());
      if (category == null) {
        if (section.hasEntry()) {
          return false;
        }
        continue;
      }
      for (org.hl7.fhir.r4.model.Reference reference : section.getEntry()) {
        if (reference.getReference() == null) {
          return false;
        }
        Resource resource = resourcesByFullUrl.get(reference.getReference());
        if (resource == null
            || !isExpectedResource(category, resource)
            || !hasExpectedPatientSubject(resource, patientFullUrl)) {
          return false;
        }
        referencedResources.add(reference.getReference());
      }
    }
    for (Map.Entry<String, Resource> entry : resourcesByFullUrl.entrySet()) {
      Resource resource = entry.getValue();
      if (resource instanceof Composition || resource instanceof Patient) {
        continue;
      }
      if (!referencedResources.contains(entry.getKey())) {
        return false;
      }
    }
    return true;
  }

  private static boolean isExpectedResource(PatientContextCategory category, Resource resource) {
    return switch (category) {
      case PROBLEMS -> resource instanceof Condition;
      case ALLERGIES -> resource instanceof AllergyIntolerance;
      case MEDICATIONS ->
          resource instanceof MedicationRequest
              || resource instanceof MedicationAdministration
              || resource instanceof MedicationStatement;
    };
  }

  private static String fullUrlOf(Map<String, Resource> resourcesByFullUrl, Resource target) {
    return resourcesByFullUrl.entrySet().stream()
        .filter(entry -> entry.getValue() == target)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("IPS Patient has no local fullUrl"));
  }

  private static boolean declaresProfile(Resource resource, String expectedProfile) {
    return resource.getMeta().getProfile().stream()
        .anyMatch(profile -> expectedProfile.equals(profile.getValue()));
  }

  private void validateNestedProfile(Resource resource, String profile) {
    byte[] serialized =
        FhirContext.forR4Cached()
            .newJsonParser()
            .encodeResourceToString(resource)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    parser.parse(serialized, FHIR_JSON, new FhirProfileContext(FHIR_RELEASE, profile));
  }

  private static boolean hasExactPatientIdentifier(
      Patient patient, PatientReference expectedPatient) {
    String expectedSystem = expectedPatient.patient().issuer().value();
    String expectedValue = expectedPatient.patient().value();
    return patient.getIdentifier().stream()
        .anyMatch(
            identifier ->
                expectedSystem.equals(identifier.getSystem())
                    && expectedValue.equals(identifier.getValue()));
  }

  private static List<PatientContextSection> projectSections(
      Composition composition,
      Map<String, Resource> resourcesByFullUrl,
      String patientFullUrl,
      Set<PatientContextCategory> permittedCategories) {
    List<PatientContextSection> result = new ArrayList<>();
    for (Composition.SectionComponent section : composition.getSection()) {
      PatientContextCategory category = category(section.getCode());
      if (category == null || !permittedCategories.contains(category)) {
        continue;
      }
      LinkedHashSet<PatientContextCode> codes = new LinkedHashSet<>();
      for (org.hl7.fhir.r4.model.Reference reference : section.getEntry()) {
        Resource entry = resourcesByFullUrl.get(reference.getReference());
        if (entry == null) {
          return null;
        }
        if (!hasExpectedPatientSubject(entry, patientFullUrl)) {
          return null;
        }
        CodeableConcept concept = codeFor(entry, category);
        if (concept == null) {
          return null;
        }
        for (org.hl7.fhir.r4.model.Coding coding : concept.getCoding()) {
          if (coding.getSystem() == null
              || coding.getSystem().isBlank()
              || coding.getCode() == null
              || coding.getCode().isBlank()) {
            continue;
          }
          codes.add(new PatientContextCode(coding.getSystem(), coding.getCode()));
          if (codes.size() > MAX_CODES_PER_SECTION) {
            return null;
          }
        }
      }
      if (!codes.isEmpty()) {
        result.add(new PatientContextSection(category, List.copyOf(codes)));
      }
    }
    return List.copyOf(result);
  }

  private static boolean hasExpectedPatientSubject(Resource resource, String patientFullUrl) {
    return switch (resource) {
      case Condition condition ->
          hasExactReference(condition.getSubject().getReference(), patientFullUrl);
      case AllergyIntolerance allergy ->
          hasExactReference(allergy.getPatient().getReference(), patientFullUrl);
      case MedicationRequest request ->
          hasExactReference(request.getSubject().getReference(), patientFullUrl);
      case MedicationAdministration administration ->
          hasExactReference(administration.getSubject().getReference(), patientFullUrl);
      case MedicationStatement statement ->
          hasExactReference(statement.getSubject().getReference(), patientFullUrl);
      default -> false;
    };
  }

  private static boolean hasExactReference(String actual, String expected) {
    return actual != null && actual.equals(expected);
  }

  private static PatientContextCategory category(CodeableConcept sectionCode) {
    if (sectionCode == null) {
      return null;
    }
    for (org.hl7.fhir.r4.model.Coding coding : sectionCode.getCoding()) {
      if (!LOINC.equals(coding.getSystem())) {
        continue;
      }
      return switch (coding.getCode()) {
        case "11450-4" -> PatientContextCategory.PROBLEMS;
        case "48765-2" -> PatientContextCategory.ALLERGIES;
        case "10160-0" -> PatientContextCategory.MEDICATIONS;
        default -> null;
      };
    }
    return null;
  }

  private static CodeableConcept codeFor(Resource resource, PatientContextCategory category) {
    return switch (category) {
      case PROBLEMS -> resource instanceof Condition condition ? condition.getCode() : null;
      case ALLERGIES -> resource instanceof AllergyIntolerance allergy ? allergy.getCode() : null;
      case MEDICATIONS ->
          switch (resource) {
            case MedicationStatement statement -> statement.getMedicationCodeableConcept();
            case MedicationRequest request -> request.getMedicationCodeableConcept();
            case MedicationAdministration administration ->
                administration.getMedicationCodeableConcept();
            default -> null;
          };
    };
  }

  private static PatientContextSourceDocument invalid(
      PatientReference patient, PatientContextSourceEvidence evidence) {
    return document(PatientContextSourceDocument.Status.INVALID, patient, List.of(), evidence);
  }

  private static PatientContextSourceDocument document(
      PatientContextSourceDocument.Status status,
      PatientReference patient,
      List<PatientContextSection> sections,
      PatientContextSourceEvidence evidence) {
    return new PatientContextSourceDocument(status, patient, sections, evidence);
  }
}
