package org.ayuvahak.directory.domain;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Facility-owned physical place with optional WGS84 coordinates and a primary marker. */
public record DirectoryLocation(
    InternalId id,
    InternalId facilityId,
    String name,
    Optional<Wgs84Coordinate> coordinate,
    boolean primary,
    DirectorySource source,
    DirectoryProvenance provenance,
    long version) {

  public DirectoryLocation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(facilityId, "facilityId");
    requireName(name);
    coordinate = Objects.requireNonNull(coordinate, "coordinate");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(provenance, "provenance");
    if (version < 1) {
      throw new IllegalArgumentException("location version must be positive");
    }
  }

  public DirectoryLocation correct(
      String correctedName,
      Optional<Wgs84Coordinate> correctedCoordinate,
      boolean correctedPrimary,
      DirectoryProvenance correction) {
    return new DirectoryLocation(
        id,
        facilityId,
        correctedName,
        correctedCoordinate,
        correctedPrimary,
        source,
        correction,
        Math.addExact(version, 1));
  }

  public DirectoryLocation withPrimary(boolean correctedPrimary, DirectoryProvenance correction) {
    return new DirectoryLocation(
        id,
        facilityId,
        name,
        coordinate,
        correctedPrimary,
        source,
        correction,
        Math.addExact(version, 1));
  }

  private static void requireName(String value) {
    if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 200) {
      throw new IllegalArgumentException("location name must be trimmed and 1 to 200 characters");
    }
  }
}
