package org.ayuvahak.referral.application;

import java.util.Objects;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.application.GrantTargetScopeValidator;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.referral.domain.ReferralAuthorities;

/** Validates Referral facility-scoped grant targets against Directory-owned active facts. */
public final class ReferralGrantTargetScopeValidator implements GrantTargetScopeValidator {

  private final DirectoryScopeReferenceLookup directory;

  public ReferralGrantTargetScopeValidator(DirectoryScopeReferenceLookup directory) {
    this.directory = Objects.requireNonNull(directory, "Directory reference lookup");
  }

  @Override
  public boolean supports(FunctionalAuthority authority) {
    return ReferralAuthorities.patientContextDisclosure().equals(authority)
        || ReferralAuthorities.reportingSnapshot().equals(authority);
  }

  @Override
  public boolean isValid(FunctionalAuthority authority, RequestedExecutionScope targetScope) {
    if (!supports(authority)) {
      return false;
    }
    if (ReferralAuthorities.reportingSnapshot().equals(authority)) {
      if (targetScope instanceof RequestedExecutionScope.Organization organization) {
        return directory.isActiveOrganization(organization.organization());
      }
      if (targetScope instanceof RequestedExecutionScope.Facility facility) {
        return directory.isActiveFacilityInOrganization(
            facility.organization(), facility.facility());
      }
      return false;
    }
    if (!(targetScope instanceof RequestedExecutionScope.Facility facility)) {
      return false;
    }
    return directory.isActiveFacilityInOrganization(facility.organization(), facility.facility());
  }
}
