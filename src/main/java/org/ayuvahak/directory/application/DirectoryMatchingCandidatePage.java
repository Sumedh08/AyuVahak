package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;

/** Bounded Directory-owned candidate evidence for one Matching evaluation time. */
public record DirectoryMatchingCandidatePage(
    ExternalReference organization,
    Instant evaluatedAt,
    List<Candidate> candidates,
    int page,
    int size,
    boolean hasMore) {

  public DirectoryMatchingCandidatePage {
    Objects.requireNonNull(organization, "candidate organization");
    Objects.requireNonNull(evaluatedAt, "candidate evaluation time");
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidate evidence"));
    if (!"Organization".equals(organization.resourceType())
        || page < 0
        || size < 1
        || candidates.size() > size
        || candidates.stream()
            .anyMatch(candidate -> !candidate.organization().equals(organization))) {
      throw new IllegalArgumentException("Directory candidate page is invalid or out of scope");
    }
  }

  public record Candidate(
      ExternalReference facility,
      ExternalReference organization,
      String displayName,
      String lifecycleStatus,
      long version,
      ExternalReference source,
      Provenance provenance,
      Optional<Location> primaryLocation,
      List<Capability> capabilities) {

    public Candidate {
      requireType(facility, "Facility");
      requireType(organization, "Organization");
      requireType(source, null);
      Objects.requireNonNull(displayName, "facility name");
      Objects.requireNonNull(lifecycleStatus, "facility status");
      Objects.requireNonNull(provenance, "facility provenance");
      primaryLocation = Objects.requireNonNull(primaryLocation, "primary location");
      capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capability evidence"));
      if (version < 1 || displayName.isBlank() || lifecycleStatus.isBlank()) {
        throw new IllegalArgumentException("candidate Facility facts are invalid");
      }
    }
  }

  public record Location(
      ExternalReference reference,
      String displayName,
      Optional<Coordinate> coordinate,
      long version,
      ExternalReference source,
      Provenance provenance) {

    public Location {
      requireType(reference, "Location");
      requireType(source, null);
      Objects.requireNonNull(displayName, "Location name");
      coordinate = Objects.requireNonNull(coordinate, "coordinate");
      Objects.requireNonNull(provenance, "Location provenance");
      if (version < 1 || displayName.isBlank()) {
        throw new IllegalArgumentException("candidate Location facts are invalid");
      }
    }
  }

  public record Coordinate(double longitude, double latitude) {

    public Coordinate {
      if (!Double.isFinite(longitude)
          || longitude < -180
          || longitude > 180
          || !Double.isFinite(latitude)
          || latitude < -90
          || latitude > 90) {
        throw new IllegalArgumentException("candidate coordinates must be valid WGS84 values");
      }
    }
  }

  public record Capability(
      String code,
      String outcome,
      Optional<Long> capabilityVersion,
      Optional<String> capabilityStatus,
      List<Assertion> assertions) {

    public Capability {
      Objects.requireNonNull(code, "CareCapability code");
      Objects.requireNonNull(outcome, "capability assessment outcome");
      capabilityVersion = Objects.requireNonNull(capabilityVersion, "capability version");
      capabilityStatus = Objects.requireNonNull(capabilityStatus, "capability status");
      assertions = List.copyOf(Objects.requireNonNull(assertions, "assertion evidence"));
    }
  }

  public record Assertion(
      ExternalReference reference,
      long version,
      String verificationStatus,
      ExternalReference source,
      Provenance provenance,
      Instant effectiveFrom,
      Optional<Instant> effectiveUntil,
      Optional<ExternalReference> healthcareService,
      boolean healthcareServiceActive) {

    public Assertion {
      requireType(reference, "FacilityCapabilityAssertion");
      requireType(source, null);
      Objects.requireNonNull(verificationStatus, "verification status");
      Objects.requireNonNull(provenance, "assertion provenance");
      Objects.requireNonNull(effectiveFrom, "effective from");
      effectiveUntil = Objects.requireNonNull(effectiveUntil, "effective until");
      healthcareService = Objects.requireNonNull(healthcareService, "HealthcareService reference");
      if (version < 1 || verificationStatus.isBlank()) {
        throw new IllegalArgumentException("capability assertion facts are invalid");
      }
      healthcareService.ifPresent(
          referenceValue -> requireType(referenceValue, "HealthcareService"));
    }
  }

  public record Provenance(
      String method, Instant observedAt, Instant recordedAt, String reasonCode) {

    public Provenance {
      Objects.requireNonNull(method, "provenance method");
      Objects.requireNonNull(observedAt, "provenance observed at");
      Objects.requireNonNull(recordedAt, "provenance recorded at");
      Objects.requireNonNull(reasonCode, "provenance reason code");
    }
  }

  private static void requireType(ExternalReference reference, String expectedType) {
    Objects.requireNonNull(reference, "reference");
    if (expectedType != null && !expectedType.equals(reference.resourceType())) {
      throw new IllegalArgumentException("candidate reference type is invalid");
    }
  }
}
