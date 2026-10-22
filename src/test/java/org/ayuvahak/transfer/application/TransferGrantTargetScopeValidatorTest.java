package org.ayuvahak.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.junit.jupiter.api.Test;

class TransferGrantTargetScopeValidatorTest {

  private static final IssuerId DIRECTORY = new IssuerId("urn:ayuvahak:directory");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(DIRECTORY, "Organization", "SYN-ORG-001");
  private static final ExternalReference FACILITY =
      new ExternalReference(DIRECTORY, "Facility", "SYN-FACILITY-001");

  @Test
  void validatesEveryScopedTransferAuthorityAgainstDirectoryFacts() {
    var validator = new TransferGrantTargetScopeValidator(activeDirectoryLookup());
    List<FunctionalAuthority> authorities =
        List.of(
            TransferAuthorities.request(),
            TransferAuthorities.requestExpiry(),
            TransferAuthorities.clinicalSuitability(),
            TransferAuthorities.operationalReadiness(),
            TransferAuthorities.acceptance(),
            TransferAuthorities.reservationReconciliation(),
            TransferAuthorities.referralCancellation(),
            TransferAuthorities.progress(),
            TransferAuthorities.handover(),
            TransferAuthorities.completion(),
            TransferAuthorities.closure(),
            TransferAuthorities.view(),
            TransferAuthorities.reportingSnapshots(),
            TransferAuthorities.patientContextParticipation(),
            TransferAuthorities.referralAmendmentCheck(),
            TransferAuthorities.transportRequestEligibility(),
            TransferAuthorities.reroute(),
            TransferAuthorities.transportDestinationChangeEligibility());

    assertThat(authorities)
        .allSatisfy(authority -> assertThat(validator.supports(authority)).isTrue());
    assertThat(
            validator.isValid(
                TransferAuthorities.request(), RequestedExecutionScope.organization(ORGANIZATION)))
        .isTrue();
    assertThat(
            validator.isValid(
                TransferAuthorities.acceptance(),
                RequestedExecutionScope.facility(ORGANIZATION, FACILITY)))
        .isTrue();
    assertThat(
            validator.isValid(
                TransferAuthorities.acceptance(),
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
