package org.ayuvahak.architecture.fixtures.domain;

import org.hl7.fhir.r4.model.Patient;

/** Test-only fixture proving production FHIR model types cannot leak into a domain package. */
public final class FhirDependentDomainFixture {

  private final Patient patient = new Patient();

  public Patient patient() {
    return patient;
  }
}
