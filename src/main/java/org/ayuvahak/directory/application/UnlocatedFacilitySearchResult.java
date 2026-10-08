package org.ayuvahak.directory.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.Facility;

/** Active Facility with no primary coordinate; it has no distance or rank. */
public record UnlocatedFacilitySearchResult(
    Facility facility, Optional<DirectoryLocation> primaryLocation) {

  public UnlocatedFacilitySearchResult {
    Objects.requireNonNull(facility, "facility");
    primaryLocation = Objects.requireNonNull(primaryLocation, "primaryLocation");
    if (primaryLocation.filter(location -> location.coordinate().isPresent()).isPresent()) {
      throw new IllegalArgumentException("unlocated result cannot have primary coordinates");
    }
  }
}
