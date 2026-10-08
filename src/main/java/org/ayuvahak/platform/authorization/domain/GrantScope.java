package org.ayuvahak.platform.authorization.domain;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;

/** Exact target scope of an Identity & Access grant. */
public record GrantScope(
    ExecutionScope.Kind kind,
    Optional<ExternalReference> organization,
    Optional<ExternalReference> facility) {

  public GrantScope {
    Objects.requireNonNull(kind, "kind");
    organization = Objects.requireNonNull(organization, "organization");
    facility = Objects.requireNonNull(facility, "facility");
    switch (kind) {
      case PLATFORM -> {
        if (organization.isPresent() || facility.isPresent()) {
          throw new IllegalArgumentException("platform grant scope cannot contain references");
        }
      }
      case ORGANIZATION -> {
        if (organization.isEmpty()
            || !"Organization".equals(organization.orElseThrow().resourceType())
            || facility.isPresent()) {
          throw new IllegalArgumentException("organization grant scope is invalid");
        }
      }
      case FACILITY -> {
        if (organization.isEmpty()
            || !"Organization".equals(organization.orElseThrow().resourceType())
            || facility.isEmpty()
            || !"Facility".equals(facility.orElseThrow().resourceType())) {
          throw new IllegalArgumentException("facility grant scope is invalid");
        }
      }
    }
  }

  public static GrantScope platform() {
    return new GrantScope(ExecutionScope.Kind.PLATFORM, Optional.empty(), Optional.empty());
  }

  public static GrantScope from(RequestedExecutionScope requestedScope) {
    Objects.requireNonNull(requestedScope, "requested grant scope");
    return switch (requestedScope) {
      case RequestedExecutionScope.Platform ignored ->
          new GrantScope(ExecutionScope.Kind.PLATFORM, Optional.empty(), Optional.empty());
      case RequestedExecutionScope.Organization organization ->
          new GrantScope(
              ExecutionScope.Kind.ORGANIZATION,
              Optional.of(organization.organization()),
              Optional.empty());
      case RequestedExecutionScope.Facility facility ->
          new GrantScope(
              ExecutionScope.Kind.FACILITY,
              Optional.of(facility.organization()),
              Optional.of(facility.facility()));
    };
  }

  public RequestedExecutionScope asRequestedScope() {
    return switch (kind) {
      case PLATFORM -> RequestedExecutionScope.platform();
      case ORGANIZATION -> RequestedExecutionScope.organization(organization.orElseThrow());
      case FACILITY ->
          RequestedExecutionScope.facility(organization.orElseThrow(), facility.orElseThrow());
    };
  }

  @Override
  public String toString() {
    return "GrantScope[kind=" + kind + ", references=redacted]";
  }
}
