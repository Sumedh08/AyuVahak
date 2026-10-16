package org.ayuvahak.transport.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.transport.domain.TransportAuthorities;
import org.junit.jupiter.api.Test;

class TransportGrantTargetScopeValidatorTest {

  @Test
  void recognizesTheSpecificationRequestAuthorityForTargetValidation() {
    var validator = new TransportGrantTargetScopeValidator(new InactiveDirectoryLookup());

    assertThat(validator.supports(TransportAuthorities.request())).isTrue();
  }

  private static final class InactiveDirectoryLookup implements DirectoryScopeReferenceLookup {

    @Override
    public boolean isActiveOrganization(org.ayuvahak.platform.core.ExternalReference organization) {
      return false;
    }

    @Override
    public boolean isActiveFacilityInOrganization(
        org.ayuvahak.platform.core.ExternalReference organization,
        org.ayuvahak.platform.core.ExternalReference facility) {
      return false;
    }
  }
}
