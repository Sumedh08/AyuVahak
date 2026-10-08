package org.ayuvahak.directory.domain;

/** Verification state of a directly attributed Facility capability assertion. */
public enum CapabilityVerificationStatus {
  UNVERIFIED,
  VERIFIED,
  DISPUTED,
  WITHDRAWN,
  SUPERSEDED
}
