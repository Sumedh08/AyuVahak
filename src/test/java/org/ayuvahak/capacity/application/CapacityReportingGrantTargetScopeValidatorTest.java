package org.ayuvahak.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.ayuvahak.capacity.domain.CapacityAuthorities;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.Test;

class CapacityReportingGrantTargetScopeValidatorTest {

  private static final IssuerId DIRECTORY = new IssuerId("urn:ayuvahak:directory");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(DIRECTORY, "Organization", "SYN-ORG-001");
  private static final ExternalReference FACILITY =
      new ExternalReference(DIRECTORY, "Facility", "SYN-FACILITY-001");

  @Test
  void validatesFacilityScopedCapacityGrantsAgainstDirectoryFacts() {
    var validator = new CapacityReportingGrantTargetScopeValidator(activeDirectoryLookup());
    var scope = RequestedExecutionScope.facility(ORGANIZATION, FACILITY);

    for (FunctionalAuthority functionalAuthority :
        List.of(
            CapacityAuthorities.reporting(),
            CapacityAuthorities.evidenceReview(),
            CapacityAuthorities.reservation())) {
      assertThat(validator.supports(functionalAuthority)).isTrue();
      assertThat(validator.isValid(functionalAuthority, scope)).isTrue();
    }
    var inactiveScope =
        RequestedExecutionScope.facility(
            ORGANIZATION, new ExternalReference(DIRECTORY, "Facility", "SYN-INACTIVE-FACILITY"));
    assertThat(validator.isValid(CapacityAuthorities.reservation(), inactiveScope)).isFalse();
  }

  private static DirectoryScopeReferenceLookup activeDirectoryLookup() {
    return new DirectoryScopeReferenceLookup() {
      @Override
      public boolean isActiveOrganization(ExternalReference organization) {
        return ORGANIZATION.equals(organization);
      }

      @Override
      public boolean isActiveFacilityInOrganization(
          ExternalReference organization, ExternalReference facility) {
        return ORGANIZATION.equals(organization) && FACILITY.equals(facility);
      }
    };
  }
}
