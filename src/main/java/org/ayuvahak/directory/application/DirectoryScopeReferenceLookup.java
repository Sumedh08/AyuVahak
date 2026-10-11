package org.ayuvahak.directory.application;

import org.ayuvahak.platform.core.ExternalReference;

/** Directory-owned read port for validating grant targets against active Directory facts. */
public interface DirectoryScopeReferenceLookup {

  boolean isActiveOrganization(ExternalReference organization);

  boolean isActiveFacilityInOrganization(
      ExternalReference organization, ExternalReference facility);
}
