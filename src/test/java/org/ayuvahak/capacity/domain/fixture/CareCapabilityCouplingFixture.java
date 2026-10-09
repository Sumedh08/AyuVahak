package org.ayuvahak.capacity.domain.fixture;

import org.ayuvahak.directory.domain.CareCapability;

/** Deliberate invalid dependency used to prove Capacity does not consume CareCapability. */
public final class CareCapabilityCouplingFixture {

  private final CareCapability forbiddenCapability;

  public CareCapabilityCouplingFixture(CareCapability forbiddenCapability) {
    this.forbiddenCapability = forbiddenCapability;
  }

  public CareCapability capability() {
    return forbiddenCapability;
  }
}
