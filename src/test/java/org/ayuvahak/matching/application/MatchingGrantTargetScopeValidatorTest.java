package org.ayuvahak.matching.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.matching.domain.MatchingAuthorities;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.Test;

class MatchingGrantTargetScopeValidatorTest {

  private static final IssuerId DIRECTORY = new IssuerId("urn:ayuvahak:directory");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(DIRECTORY, "Organization", "SYN-ORG-001");
  private static final ExternalReference FACILITY =
      new ExternalReference(DIRECTORY, "Facility", "SYN-FACILITY-001");

  @Test
  void validatesRecommendationGrantsAgainstActiveDirectoryScopes() {
    var validator = new MatchingGrantTargetScopeValidator(activeDirectoryLookup());
    var facilityScope = RequestedExecutionScope.facility(ORGANIZATION, FACILITY);

    assertThat(validator.supports(MatchingAuthorities.requestRecommendation())).isTrue();
    assertThat(
            validator.isValid(
                MatchingAuthorities.requestRecommendation(),
                RequestedExecutionScope.organization(ORGANIZATION)))
        .isTrue();
    assertThat(validator.isValid(MatchingAuthorities.requestRecommendation(), facilityScope))
        .isTrue();
    assertThat(validator.supports(MatchingAuthorities.readRecommendation())).isTrue();
    assertThat(validator.isValid(MatchingAuthorities.readRecommendation(), facilityScope)).isTrue();
    assertThat(
            validator.isValid(
                MatchingAuthorities.requestRecommendation(),
                RequestedExecutionScope.facility(
                    ORGANIZATION,
                    new ExternalReference(DIRECTORY, "Facility", "SYN-INACTIVE-FACILITY"))))
        .isFalse();
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
