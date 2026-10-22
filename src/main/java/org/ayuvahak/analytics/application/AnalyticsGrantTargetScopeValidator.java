package org.ayuvahak.analytics.application;

import java.util.Objects;
import java.util.Set;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.application.GrantTargetScopeValidator;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;

/** Validates Analytics grants against active Directory-owned organization and facility facts. */
public final class AnalyticsGrantTargetScopeValidator implements GrantTargetScopeValidator {

  private static final Set<FunctionalAuthority> SCOPED_AUTHORITIES =
      Set.of(
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

  private final DirectoryScopeReferenceLookup directory;

  public AnalyticsGrantTargetScopeValidator(DirectoryScopeReferenceLookup directory) {
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
