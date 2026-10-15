package org.ayuvahak.directory.application;

import java.util.Objects;
import org.ayuvahak.platform.authorization.application.GrantTargetScopeValidator;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScopeOwnerValidator;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;

/** Validates Directory-scoped grant targets through Directory-owned facts. */
public final class DirectoryGrantTargetScopeValidator
    implements GrantTargetScopeValidator, ExecutionScopeOwnerValidator {

  private final DirectoryScopeReferenceLookup directory;

  public DirectoryGrantTargetScopeValidator(DirectoryScopeReferenceLookup directory) {
    this.directory = Objects.requireNonNull(directory, "directory reference lookup");
  }

  @Override
  public boolean supports(FunctionalAuthority authority) {
    return switch (authority.code()) {
      case "DirectoryRead", "DirectoryCuration", "DirectoryMcsdSourceFeedImport" -> true;
      default -> false;
    };
  }

  @Override
  public boolean supports(RequestedExecutionScope requestedScope) {
    return requestedScope instanceof RequestedExecutionScope.Organization
        || requestedScope instanceof RequestedExecutionScope.Facility;
  }

  @Override
  public boolean isValid(RequestedExecutionScope requestedScope) {
    return switch (requestedScope) {
      case RequestedExecutionScope.Platform ignored -> true;
      case RequestedExecutionScope.Organization organization ->
          directory.isActiveOrganization(organization.organization());
      case RequestedExecutionScope.Facility facility ->
          directory.isActiveFacilityInOrganization(facility.organization(), facility.facility());
    };
  }

  @Override
  public boolean isValid(FunctionalAuthority authority, RequestedExecutionScope targetScope) {
    if (!supports(authority)) {
      return false;
    }
    return isValid(targetScope);
  }
}
