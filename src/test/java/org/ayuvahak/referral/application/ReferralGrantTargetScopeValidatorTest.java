package org.ayuvahak.referral.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.referral.domain.ReferralAuthorities;
import org.junit.jupiter.api.Test;

class ReferralGrantTargetScopeValidatorTest {

  private static final IssuerId DIRECTORY = new IssuerId("urn:ayuvahak:directory");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(DIRECTORY, "Organization", "SYN-ORG-001");
  private static final ExternalReference FACILITY =
      new ExternalReference(DIRECTORY, "Facility", "SYN-FACILITY-001");

  @Test
  void validatesDisclosureGrantOnlyForAnActiveFacilityInItsOrganization() {
    var validator =
        new ReferralGrantTargetScopeValidator(
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
    var validScope = RequestedExecutionScope.facility(ORGANIZATION, FACILITY);

    assertThat(validator.supports(ReferralAuthorities.patientContextDisclosure())).isTrue();
    assertThat(validator.isValid(ReferralAuthorities.patientContextDisclosure(), validScope))
        .isTrue();
    assertThat(
            validator.isValid(
                ReferralAuthorities.patientContextDisclosure(),
                RequestedExecutionScope.facility(
                    ORGANIZATION,
                    new ExternalReference(DIRECTORY, "Facility", "SYN-INACTIVE-FACILITY"))))
        .isFalse();
    assertThat(
            validator.isValid(
                ReferralAuthorities.patientContextDisclosure(),
                RequestedExecutionScope.facility(
                    new ExternalReference(DIRECTORY, "Organization", "SYN-OTHER-ORG"), FACILITY)))
        .isFalse();
    assertThat(
            validator.isValid(
                new FunctionalAuthority(
                    "UnrelatedFacilityAuthority", java.util.Set.of(ExecutionScope.Kind.FACILITY)),
                validScope))
        .isFalse();
  }

  @Test
  void validatesOperationalReferralGrantsAgainstActiveDirectoryScopes() {
    var validator =
        new ReferralGrantTargetScopeValidator(
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
    var organizationScope = RequestedExecutionScope.organization(ORGANIZATION);
    var facilityScope = RequestedExecutionScope.facility(ORGANIZATION, FACILITY);

    assertThat(validator.supports(ReferralAuthorities.create())).isTrue();
    assertThat(validator.isValid(ReferralAuthorities.create(), organizationScope)).isTrue();
    assertThat(validator.isValid(ReferralAuthorities.create(), facilityScope)).isTrue();
    assertThat(
            validator.isValid(
                ReferralAuthorities.create(),
                RequestedExecutionScope.facility(
                    ORGANIZATION,
                    new ExternalReference(DIRECTORY, "Facility", "SYN-INACTIVE-FACILITY"))))
        .isFalse();
    assertThat(validator.supports(ReferralAuthorities.submit())).isTrue();
    assertThat(validator.isValid(ReferralAuthorities.submit(), facilityScope)).isTrue();
    assertThat(validator.supports(ReferralAuthorities.read())).isTrue();
    assertThat(validator.isValid(ReferralAuthorities.read(), organizationScope)).isTrue();
    assertThat(validator.supports(ReferralAuthorities.retention())).isTrue();
    assertThat(validator.isValid(ReferralAuthorities.retention(), facilityScope)).isTrue();
  }

  @Test
  void validatesReportingGrantForAnActiveOrganizationOrFacility() {
    var validator =
        new ReferralGrantTargetScopeValidator(
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

    assertThat(validator.supports(ReferralAuthorities.reportingSnapshot())).isTrue();
    assertThat(
            validator.isValid(
                ReferralAuthorities.reportingSnapshot(),
                RequestedExecutionScope.organization(ORGANIZATION)))
        .isTrue();
    assertThat(
            validator.isValid(
                ReferralAuthorities.reportingSnapshot(),
                RequestedExecutionScope.facility(ORGANIZATION, FACILITY)))
        .isTrue();
    assertThat(
            validator.isValid(
                ReferralAuthorities.reportingSnapshot(),
                RequestedExecutionScope.facility(
                    ORGANIZATION,
                    new ExternalReference(DIRECTORY, "Facility", "SYN-INACTIVE-FACILITY"))))
        .isFalse();
    assertThat(
            validator.isValid(
                ReferralAuthorities.reportingSnapshot(),
                RequestedExecutionScope.organization(
                    new ExternalReference(DIRECTORY, "Organization", "SYN-INACTIVE-ORG"))))
        .isFalse();
  }
}
