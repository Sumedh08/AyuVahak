package org.ayuvahak.directory.adapter.application;

import java.util.Objects;
import org.ayuvahak.directory.application.DirectoryReferralReferenceLookup;
import org.ayuvahak.directory.application.DirectoryStore;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.platform.core.ExternalReference;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Maps Referral's narrow Directory contract to Directory-owned persistence facts. */
@Component
@Lazy
public final class DirectoryReferralReferenceAdapter implements DirectoryReferralReferenceLookup {

  private final DirectoryStore store;

  public DirectoryReferralReferenceAdapter(@Lazy DirectoryStore store) {
    this.store = Objects.requireNonNull(store, "Directory store");
  }

  @Override
  public boolean isActiveOrganization(ExternalReference organization) {
    return store.isActiveOrganization(organization);
  }

  @Override
  public boolean isActiveFacilityInOrganization(
      ExternalReference organization, ExternalReference facility) {
    return store.isActiveFacilityInOrganization(organization, facility);
  }

  @Override
  public boolean isActiveCareCapabilityCode(String code) {
    return store
        .findCareCapabilityByCode(code)
        .filter(capability -> capability.status() == DirectoryLifecycleStatus.ACTIVE)
        .isPresent();
  }
}
