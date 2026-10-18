package org.ayuvahak.analytics.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Least-privilege application grants owned by Analytics. */
public final class AnalyticsAuthorities {

  private AnalyticsAuthorities() {}

  public static FunctionalAuthority aggregateComputation() {
    return new FunctionalAuthority(
        "AnalyticsAggregateComputation", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }

  public static FunctionalAuthority lateAggregateRevision() {
    return new FunctionalAuthority(
        "AnalyticsLateAggregateRevision", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }

  public static FunctionalAuthority aggregateSnapshotFinalization() {
    return new FunctionalAuthority(
        "AnalyticsAggregateSnapshotFinalization", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }

  public static FunctionalAuthority aggregateSnapshotCorrection() {
    return new FunctionalAuthority(
        "AnalyticsAggregateSnapshotCorrection", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }
}
