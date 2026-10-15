package org.ayuvahak.platform.fhir;

import java.util.Objects;
import org.hl7.fhir.instance.model.api.IBaseResource;

public record FhirR4ParsedResource(
    IBaseResource resource,
    String fhirRelease,
    String profileUri,
    FhirTerminologyAssessment terminologyAssessment) {

  public FhirR4ParsedResource(IBaseResource resource, String fhirRelease, String profileUri) {
    this(resource, fhirRelease, profileUri, FhirTerminologyAssessment.NOT_ASSESSED);
  }

  public FhirR4ParsedResource {
    Objects.requireNonNull(resource);
    Objects.requireNonNull(fhirRelease);
    Objects.requireNonNull(profileUri);
    Objects.requireNonNull(terminologyAssessment);
  }
}
