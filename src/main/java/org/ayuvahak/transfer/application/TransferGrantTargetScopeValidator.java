package org.ayuvahak.transfer.application;

import java.util.Objects;
import java.util.Set;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.application.GrantTargetScopeValidator;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.transfer.domain.TransferAuthorities;

/**
 * Validates scoped Transfer grants against active Directory-owned organization and facility facts.
 */
public final class TransferGrantTargetScopeValidator implements GrantTargetScopeValidator {

  private static final Set<FunctionalAuthority> SCOPED_AUTHORITIES =
      Set.of(
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

  private final DirectoryScopeReferenceLookup directory;

  public TransferGrantTargetScopeValidator(DirectoryScopeReferenceLookup directory) {
    this.directory = Objects.requireNonNull(directory, "Directory reference lookup");
  }

  @Override
  public boolean supports(FunctionalAuthority authority) {
    return SCOPED_AUTHORITIES.contains(authority);
  }

  @Override
  public boolean isValid(FunctionalAuthority authority, RequestedExecutionScope targetScope) {
    if (!supports(authority)) return false;
    return switch (targetScope) {
      case RequestedExecutionScope.Organization organization ->
          directory.isActiveOrganization(organization.organization());
      case RequestedExecutionScope.Facility facility ->
          directory.isActiveFacilityInOrganization(facility.organization(), facility.facility());
      case RequestedExecutionScope.Platform ignored -> false;
    };
  }
}
