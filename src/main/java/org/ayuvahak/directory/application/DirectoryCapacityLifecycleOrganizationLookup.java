package org.ayuvahak.directory.application;

import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Owner contract for Capacity lifecycle work to resolve one Facility's active Organization. */
public interface DirectoryCapacityLifecycleOrganizationLookup {

  Optional<ExternalReference> getCapacityLifecycleFacilityOrganization(
      ExecutionContext context, ExternalReference facility);
}
