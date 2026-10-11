package org.ayuvahak.directory.application;

import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;

/** Directory-owned read port for validating grant targets against active Directory facts. */
public interface DirectoryScopeReferenceLookup {

  boolean isActiveOrganization(ExternalReference organization);

  boolean isActiveFacilityInOrganization(
      ExternalReference organization, ExternalReference facility);

  default Optional<ExternalReference> findActiveFacilityOrganization(ExternalReference facility) {
    return Optional.empty();
  }
}
