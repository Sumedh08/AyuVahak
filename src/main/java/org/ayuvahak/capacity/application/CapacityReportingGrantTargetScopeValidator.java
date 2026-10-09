package org.ayuvahak.capacity.application;

import java.util.Objects;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.application.GrantTargetScopeValidator;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;

/** Validates Capacity reporter and evidence-review grants against active Directory Facilities. */
public final class CapacityReportingGrantTargetScopeValidator implements GrantTargetScopeValidator {

  private final DirectoryScopeReferenceLookup directory;

  public CapacityReportingGrantTargetScopeValidator(DirectoryScopeReferenceLookup directory) {
    this.directory = Objects.requireNonNull(directory, "Directory reference lookup");
  }

  @Override
  public boolean supports(FunctionalAuthority authority) {
    return "CapacityReporting".equals(authority.code())
        || "CapacityEvidenceReview".equals(authority.code());
  }

  @Override
  public boolean isValid(FunctionalAuthority authority, RequestedExecutionScope targetScope) {
    if (!supports(authority)
        || !(targetScope instanceof RequestedExecutionScope.Facility facility)) {
      return false;
    }
    return directory.isActiveFacilityInOrganization(facility.organization(), facility.facility());
  }
}
