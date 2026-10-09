package org.ayuvahak.directory.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.Facility;

/** Facility projection and its optional primary Location returned by bounded Directory search. */
public record DirectoryFacilitySearchEntry(
    Facility facility, Optional<DirectoryLocation> primaryLocation) {

  public DirectoryFacilitySearchEntry {
    Objects.requireNonNull(facility, "facility");
    primaryLocation = Objects.requireNonNull(primaryLocation, "primaryLocation");
    if (primaryLocation
        .filter(location -> !location.facilityId().equals(facility.id()))
        .isPresent()) {
      throw new IllegalArgumentException("primary Location must belong to the returned Facility");
    }
    if (primaryLocation.filter(location -> !location.primary()).isPresent()) {
      throw new IllegalArgumentException("search Location must be the Facility's primary Location");
    }
  }
}
