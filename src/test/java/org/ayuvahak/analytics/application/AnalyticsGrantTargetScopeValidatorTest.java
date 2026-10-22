package org.ayuvahak.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.Test;

class AnalyticsGrantTargetScopeValidatorTest {

  private static final IssuerId DIRECTORY = new IssuerId("urn:ayuvahak:directory");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(DIRECTORY, "Organization", "SYN-ORG-001");
  private static final ExternalReference FACILITY =
      new ExternalReference(DIRECTORY, "Facility", "SYN-FACILITY-001");

  @Test
  void validatesOrganizationAndFacilityScopedAnalyticsAuthorities() {
    var validator = new AnalyticsGrantTargetScopeValidator(activeDirectoryLookup());
    List<FunctionalAuthority> scopedAuthorities =
        List.of(
            AnalyticsAuthorities.aggregateComputation(),
            AnalyticsAuthorities.aggregateRead(),
            AnalyticsAuthorities.lateAggregateRevision(),
            AnalyticsAuthorities.aggregateSnapshotFinalization(),
            AnalyticsAuthorities.aggregateSnapshotCorrection(),
            AnalyticsAuthorities.disclosurePolicyAdministration(),
            AnalyticsAuthorities.disclosureRelease(),
            AnalyticsAuthorities.disclosureReservationLifecycle(),
            AnalyticsAuthorities.disclosureReview(),
            AnalyticsAuthorities.dhis2MappingAdministration(),
            AnalyticsAuthorities.dhis2Export(),
            AnalyticsAuthorities.dhis2ExportReconciliation(),
            AnalyticsAuthorities.federatedAggregateMappingAdministration(),
            AnalyticsAuthorities.federatedAggregatePublication());

    assertThat(scopedAuthorities)
        .allSatisfy(authority -> assertThat(validator.supports(authority)).isTrue());
    assertThat(validator.supports(AnalyticsAuthorities.indicatorDefinitionAdministration()))
        .isFalse();
    assertThat(
            validator.isValid(
                AnalyticsAuthorities.aggregateRead(),
                RequestedExecutionScope.organization(ORGANIZATION)))
        .isTrue();
    assertThat(
            validator.isValid(
                AnalyticsAuthorities.aggregateRead(),
                RequestedExecutionScope.facility(ORGANIZATION, FACILITY)))
        .isTrue();
    assertThat(
            validator.isValid(
                AnalyticsAuthorities.aggregateRead(),
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
