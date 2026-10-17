package org.ayuvahak.matching.domain;

/** Administrator-approved source versions that a Matching deployment can interpret. */
public record RemoteCapacityEvidenceCompatibility(
    String sourceProfileVersion, long dimensionVersion, long policyVersion) {

  public RemoteCapacityEvidenceCompatibility {
    if (sourceProfileVersion == null
        || !sourceProfileVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("source profile version must be a stable identifier");
    }
    if (dimensionVersion < 1 || policyVersion < 1) {
      throw new IllegalArgumentException("compatible source versions must be positive");
    }
  }
}
