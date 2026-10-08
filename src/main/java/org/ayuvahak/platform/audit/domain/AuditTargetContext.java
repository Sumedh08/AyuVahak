package org.ayuvahak.platform.audit.domain;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Optional, minimized scope of the target affected by an audited action. */
public record AuditTargetContext(
    ExecutionScope.Kind kind,
    Optional<ExternalReference> organization,
    Optional<ExternalReference> facility) {

  public AuditTargetContext {
    Objects.requireNonNull(kind, "kind");
    organization = Objects.requireNonNull(organization, "organization");
    facility = Objects.requireNonNull(facility, "facility");
    switch (kind) {
      case PLATFORM -> {
        if (organization.isPresent() || facility.isPresent()) {
          throw new IllegalArgumentException("platform target context cannot contain references");
        }
      }
      case ORGANIZATION -> {
        if (organization.isEmpty()
            || !"Organization".equals(organization.orElseThrow().resourceType())
            || facility.isPresent()) {
          throw new IllegalArgumentException("organization target context is invalid");
        }
      }
      case FACILITY -> {
        if (organization.isEmpty()
            || !"Organization".equals(organization.orElseThrow().resourceType())
            || facility.isEmpty()
            || !"Facility".equals(facility.orElseThrow().resourceType())) {
          throw new IllegalArgumentException("facility target context is invalid");
        }
      }
    }
  }

  @Override
  public String toString() {
    return "AuditTargetContext[kind=" + kind + ", references=redacted]";
  }
}
