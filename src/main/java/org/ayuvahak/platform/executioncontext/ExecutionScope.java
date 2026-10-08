package org.ayuvahak.platform.executioncontext;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;

/** A server-validated platform, organization, or facility execution scope. */
public final class ExecutionScope {

  public enum Kind {
    PLATFORM,
    ORGANIZATION,
    FACILITY
  }

  private final Kind kind;
  private final ExternalReference organization;
  private final ExternalReference facility;

  private ExecutionScope(Kind kind, ExternalReference organization, ExternalReference facility) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.organization = organization;
    this.facility = facility;
    validateShape();
  }

  public static ExecutionScope platform() {
    return new ExecutionScope(Kind.PLATFORM, null, null);
  }

  static ExecutionScope validateFor(
      ActorReference verifiedActor,
      RequestedExecutionScope requestedScope,
      ExecutionScopeValidator validator) {
    Objects.requireNonNull(verifiedActor, "verified actor");
    if (requestedScope == null) {
      throw new ExecutionScopeDeniedException();
    }
    Objects.requireNonNull(validator, "scope validator");
    if (!validator.isValidFor(verifiedActor, requestedScope)) {
      throw new ExecutionScopeDeniedException();
    }
    return fromRequested(requestedScope);
  }

  private static ExecutionScope fromRequested(RequestedExecutionScope requestedScope) {
    return switch (requestedScope) {
      case RequestedExecutionScope.Platform ignored ->
          new ExecutionScope(Kind.PLATFORM, null, null);
      case RequestedExecutionScope.Organization organizationScope ->
          new ExecutionScope(Kind.ORGANIZATION, organizationScope.organization(), null);
      case RequestedExecutionScope.Facility facilityScope ->
          new ExecutionScope(Kind.FACILITY, facilityScope.organization(), facilityScope.facility());
    };
  }

  private void validateShape() {
    switch (kind) {
      case PLATFORM -> {
        if (organization != null || facility != null) {
          throw new IllegalArgumentException(
              "platform scope must not contain organization references");
        }
      }
      case ORGANIZATION -> {
        if (organization == null
            || !"Organization".equals(organization.resourceType())
            || facility != null) {
          throw new IllegalArgumentException(
              "organization scope must contain only an organization reference");
        }
      }
      case FACILITY -> {
        if (organization == null
            || !"Organization".equals(organization.resourceType())
            || facility == null
            || !"Facility".equals(facility.resourceType())) {
          throw new IllegalArgumentException(
              "facility scope must contain matching organization and facility reference types");
        }
      }
    }
  }

  public Kind kind() {
    return kind;
  }

  public Optional<ExternalReference> organization() {
    return Optional.ofNullable(organization);
  }

  public Optional<ExternalReference> facility() {
    return Optional.ofNullable(facility);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ExecutionScope that)) {
      return false;
    }
    return kind == that.kind
        && Objects.equals(organization, that.organization)
        && Objects.equals(facility, that.facility);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, organization, facility);
  }

  @Override
  public String toString() {
    return "ExecutionScope[kind=" + kind + ", references=redacted]";
  }
}
