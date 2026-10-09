package org.ayuvahak.directory.application;

import org.ayuvahak.platform.core.ExternalReference;

/** Directory-owned, read-only facts needed to validate an originating Referral draft. */
public interface DirectoryReferralReferenceLookup {

  boolean isActiveOrganization(ExternalReference organization);

  boolean isActiveFacilityInOrganization(
      ExternalReference organization, ExternalReference facility);

  boolean isActiveCareCapabilityCode(String code);
}
