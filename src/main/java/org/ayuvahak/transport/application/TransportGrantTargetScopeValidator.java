package org.ayuvahak.transport.application;

import java.util.Objects;
import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.application.GrantTargetScopeValidator;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;

/** Validates Transport grants against active Directory-owned facility references. */
public final class TransportGrantTargetScopeValidator implements GrantTargetScopeValidator {

  private final DirectoryScopeReferenceLookup directory;

  public TransportGrantTargetScopeValidator(DirectoryScopeReferenceLookup directory) {
    this.directory = Objects.requireNonNull(directory, "Directory scope reference lookup");
  }

  @Override
  public boolean supports(FunctionalAuthority authority) {
    return switch (authority.code()) {
      case "TransportRequestAuthority",
          "TransportDispatchCoordinationAuthority",
          "TransportStatusAuthority",
          "TransportCancellationAuthority",
          "TransportDestinationChangeAuthority" ->
          true;
      default -> false;
    };
  }

  @Override
  public boolean isValid(FunctionalAuthority authority, RequestedExecutionScope targetScope) {
    if (!supports(authority)
        || !(targetScope instanceof RequestedExecutionScope.Facility facility)) {
      return false;
    }
    return directory.isActiveFacilityInOrganization(facility.organization(), facility.facility());
  }
}
