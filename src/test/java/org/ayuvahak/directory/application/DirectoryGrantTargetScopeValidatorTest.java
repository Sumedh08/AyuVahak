package org.ayuvahak.directory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.ayuvahak.directory.domain.DirectoryAuthorities;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.Test;

class DirectoryGrantTargetScopeValidatorTest {

  private static final IssuerId DIRECTORY = new IssuerId("urn:ayuvahak:directory");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(DIRECTORY, "Organization", "org-17");
  private static final ExternalReference FACILITY =
      new ExternalReference(DIRECTORY, "Facility", "facility-23");

  @Test
  void validatesOrganizationAndFacilityTargetsOnlyThroughDirectoryOwnedFacts() {
    var validator =
        new DirectoryGrantTargetScopeValidator(
            new DirectoryScopeReferenceLookup() {
              @Override
              public boolean isActiveOrganization(ExternalReference organization) {
                return ORGANIZATION.equals(organization);
              }

              @Override
              public boolean isActiveFacilityInOrganization(
                  ExternalReference organization, ExternalReference facility) {
                return ORGANIZATION.equals(organization) && FACILITY.equals(facility);
              }
            });

    assertThat(
            validator.isValid(
                DirectoryAuthorities.read(), RequestedExecutionScope.organization(ORGANIZATION)))
        .isTrue();
    assertThat(validator.supports(DirectoryAuthorities.mcsdSourceFeedImport())).isTrue();
    assertThat(
            validator.isValid(
                DirectoryAuthorities.mcsdSourceFeedImport(),
                RequestedExecutionScope.organization(ORGANIZATION)))
        .isTrue();
    assertThat(
            validator.isValid(
                DirectoryAuthorities.curation(),
                RequestedExecutionScope.facility(ORGANIZATION, FACILITY)))
        .isTrue();
    assertThat(validator.supports(RequestedExecutionScope.organization(ORGANIZATION))).isTrue();
    assertThat(validator.isValid(RequestedExecutionScope.facility(ORGANIZATION, FACILITY)))
        .isTrue();
    assertThat(
            validator.isValid(
                RequestedExecutionScope.facility(
                    ORGANIZATION, new ExternalReference(DIRECTORY, "Facility", "other-facility"))))
        .isFalse();
    assertThat(
            validator.isValid(
                DirectoryAuthorities.read(),
                RequestedExecutionScope.facility(
                    ORGANIZATION, new ExternalReference(DIRECTORY, "Facility", "other-facility"))))
        .isFalse();
    assertThat(
            validator.isValid(
                new org.ayuvahak.platform.authorization.domain.FunctionalAuthority(
                    "Unrelated", Set.of(ExecutionScope.Kind.ORGANIZATION)),
                RequestedExecutionScope.organization(ORGANIZATION)))
        .isFalse();
  }
}
