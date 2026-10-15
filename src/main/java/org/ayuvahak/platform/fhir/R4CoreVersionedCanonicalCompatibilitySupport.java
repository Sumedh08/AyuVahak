package org.ayuvahak.platform.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.BaseValidationSupportWrapper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.StructureDefinition;

/** Resolves exact R4 core versioned canonicals around HAPI's versioned-reference lookup gap. */
final class R4CoreVersionedCanonicalCompatibilitySupport extends BaseValidationSupportWrapper {

  private static final String R4_RELEASE = "4.0.1";
  private static final String CORE_STRUCTURE_DEFINITION_PREFIX =
      "http://hl7.org/fhir/StructureDefinition/";

  R4CoreVersionedCanonicalCompatibilitySupport(FhirContext context, IValidationSupport delegate) {
    super(context, delegate);
  }

  @Override
  public <T extends IBaseResource> T fetchResource(Class<T> resourceType, String uri) {
    if (resourceType != null && StructureDefinition.class.equals(resourceType)) {
      IBaseResource definition = fetchStructureDefinition(uri);
      return resourceType.isInstance(definition) ? resourceType.cast(definition) : null;
    }
    return super.fetchResource(resourceType, uri);
  }

  @Override
  public IBaseResource fetchStructureDefinition(String canonical) {
    if (canonical == null) {
      return null;
    }
    if (!canonical.startsWith(CORE_STRUCTURE_DEFINITION_PREFIX)) {
      return super.fetchStructureDefinition(canonical);
    }

    int versionSeparator = canonical.lastIndexOf('|');
    if (versionSeparator < 0) {
      return super.fetchStructureDefinition(canonical);
    }
    if (!R4_RELEASE.equals(canonical.substring(versionSeparator + 1))) {
      return null;
    }

    String coreCanonical = canonical.substring(0, versionSeparator);
    if (!isCoreStructureDefinitionCanonical(coreCanonical)) {
      return null;
    }
    IBaseResource resource = super.fetchStructureDefinition(coreCanonical);
    if (resource instanceof StructureDefinition definition
        && R4_RELEASE.equals(definition.getVersion())) {
      return definition;
    }
    return null;
  }

  private static boolean isCoreStructureDefinitionCanonical(String canonical) {
    String name = canonical.substring(CORE_STRUCTURE_DEFINITION_PREFIX.length());
    return !name.isBlank() && name.matches("[A-Za-z][A-Za-z0-9-]*");
  }
}
