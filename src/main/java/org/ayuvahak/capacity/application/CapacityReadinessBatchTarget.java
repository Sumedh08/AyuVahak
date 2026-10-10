package org.ayuvahak.capacity.application;

import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** One explicitly scoped owner-read target in a Matching readiness batch. */
public record CapacityReadinessBatchTarget(ExecutionContext context, ExternalReference facility) {

  public CapacityReadinessBatchTarget {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(facility, "facility");
    if (!"Facility".equals(facility.resourceType())) {
      throw new IllegalArgumentException("readiness batch target must be a Facility");
    }
  }
}
