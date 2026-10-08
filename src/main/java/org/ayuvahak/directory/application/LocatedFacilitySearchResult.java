package org.ayuvahak.directory.application;

import java.util.Objects;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.Facility;

/** Facility whose primary Location is inside the requested radius. */
public record LocatedFacilitySearchResult(
    Facility facility, DirectoryLocation location, double distanceMeters) {

  public LocatedFacilitySearchResult {
    Objects.requireNonNull(facility, "facility");
    Objects.requireNonNull(location, "location");
    if (!Double.isFinite(distanceMeters) || distanceMeters < 0.0) {
      throw new IllegalArgumentException("distance must be finite and non-negative");
    }
  }
}
