package org.ayuvahak.matching.application;

import java.util.Objects;
import java.util.Set;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.matching.domain.MatchingAuthorities;
import org.ayuvahak.platform.authorization.application.GrantTargetScopeValidator;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;

/** Validates Matching grants against active Directory-owned organization and facility facts. */
public final class MatchingGrantTargetScopeValidator implements GrantTargetScopeValidator {

  private static final Set<FunctionalAuthority> SCOPED_AUTHORITIES =
      Set.of(MatchingAuthorities.requestRecommendation(), MatchingAuthorities.readRecommendation());

  private final DirectoryScopeReferenceLookup directory;

  public MatchingGrantTargetScopeValidator(DirectoryScopeReferenceLookup directory) {
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
