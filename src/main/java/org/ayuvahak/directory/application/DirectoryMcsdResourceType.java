package org.ayuvahak.directory.application;

/** Directory resource types permitted by the Phase 8 mCSD projection. */
public enum DirectoryMcsdResourceType {
  ORGANIZATION("Organization"),
  LOCATION("Location"),
  HEALTHCARE_SERVICE("HealthcareService"),
  ENDPOINT("Endpoint");

  private final String fhirType;

  DirectoryMcsdResourceType(String fhirType) {
    this.fhirType = fhirType;
  }

  public String fhirType() {
    return fhirType;
  }
}
