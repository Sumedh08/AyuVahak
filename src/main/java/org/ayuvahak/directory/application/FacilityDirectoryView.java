package org.ayuvahak.directory.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryLocation;
import org.ayuvahak.directory.domain.Facility;

/** Authorized Facility projection with its Directory-owned Locations. */
public record FacilityDirectoryView(Facility facility, List<DirectoryLocation> locations) {

  public FacilityDirectoryView {
    Objects.requireNonNull(facility, "facility");
    locations = List.copyOf(Objects.requireNonNull(locations, "locations"));
  }

  public Optional<DirectoryLocation> primaryLocation() {
    return locations.stream().filter(DirectoryLocation::primary).findFirst();
  }
}
