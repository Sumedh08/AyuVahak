package org.ayuvahak.directory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Direct, attributed evidence that one Facility provides one governed CareCapability. */
public record FacilityCapabilityAssertion(
    InternalId id,
    InternalId facilityId,
    InternalId capabilityId,
    Optional<InternalId> healthcareServiceId,
    CapabilityVerificationStatus verificationStatus,
    DirectorySource source,
    DirectoryProvenance provenance,
    Instant effectiveFrom,
    Optional<Instant> effectiveUntil,
    long version) {

  public FacilityCapabilityAssertion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(facilityId, "facilityId");
    Objects.requireNonNull(capabilityId, "capabilityId");
    healthcareServiceId = Objects.requireNonNull(healthcareServiceId, "healthcareServiceId");
    Objects.requireNonNull(verificationStatus, "verificationStatus");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    effectiveUntil = Objects.requireNonNull(effectiveUntil, "effectiveUntil");
    if (effectiveUntil.filter(until -> !until.isAfter(effectiveFrom)).isPresent()) {
      throw new IllegalArgumentException("effectiveUntil must be after effectiveFrom");
    }
    if (version < 1) {
      throw new IllegalArgumentException("assertion version must be positive");
    }
  }

  public FacilityCapabilityAssertion correct(
      CapabilityVerificationStatus correctedStatus,
      DirectorySource correctedSource,
      DirectoryProvenance correction,
      Instant correctedEffectiveFrom,
      Optional<Instant> correctedEffectiveUntil) {
    return new FacilityCapabilityAssertion(
        id,
        facilityId,
        capabilityId,
        healthcareServiceId,
        correctedStatus,
        correctedSource,
        correction,
        correctedEffectiveFrom,
        correctedEffectiveUntil,
        Math.addExact(version, 1));
  }

  public boolean isEffectiveAt(Instant instant) {
    return !instant.isBefore(effectiveFrom) && effectiveUntil.map(instant::isBefore).orElse(true);
  }
}
