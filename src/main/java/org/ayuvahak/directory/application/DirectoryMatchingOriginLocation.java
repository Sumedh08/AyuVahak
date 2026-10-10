package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;

/** Minimal Directory-owned origin evidence for one Matching evaluation. */
public record DirectoryMatchingOriginLocation(
    ExternalReference facility,
    ExternalReference organization,
    String lifecycleStatus,
    long facilityVersion,
    ExternalReference source,
    Provenance provenance,
    Optional<Location> primaryLocation,
    Instant evaluatedAt) {

  public DirectoryMatchingOriginLocation {
    requireType(facility, "Facility");
    requireType(organization, "Organization");
    Objects.requireNonNull(lifecycleStatus, "Facility lifecycle status");
    if (facilityVersion < 1 || lifecycleStatus.isBlank()) {
      throw new IllegalArgumentException("origin Facility evidence is invalid");
    }
    Objects.requireNonNull(source, "Facility source");
    Objects.requireNonNull(provenance, "Facility provenance");
    primaryLocation = Objects.requireNonNull(primaryLocation, "primary Location");
    Objects.requireNonNull(evaluatedAt, "evaluation time");
  }

  public record Location(
      ExternalReference reference,
      Optional<Coordinate> coordinate,
      long version,
      ExternalReference source,
      Provenance provenance) {

    public Location {
      requireType(reference, "Location");
      coordinate = Objects.requireNonNull(coordinate, "coordinate");
      if (version < 1) {
        throw new IllegalArgumentException("origin Location version must be positive");
      }
      Objects.requireNonNull(source, "Location source");
      Objects.requireNonNull(provenance, "Location provenance");
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
        throw new IllegalArgumentException("origin coordinates must be valid WGS84 values");
      }
    }
  }

  public record Provenance(
      String method, Instant observedAt, Instant recordedAt, String reasonCode) {

    public Provenance {
      Objects.requireNonNull(method, "provenance method");
      Objects.requireNonNull(observedAt, "provenance observed time");
      Objects.requireNonNull(recordedAt, "provenance recorded time");
      Objects.requireNonNull(reasonCode, "provenance reason code");
    }
  }

  private static void requireType(ExternalReference reference, String type) {
    Objects.requireNonNull(reference, "reference");
    if (!type.equals(reference.resourceType())) {
      throw new IllegalArgumentException("origin evidence reference type is invalid");
    }
  }
}
