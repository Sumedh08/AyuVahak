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

  public static FunctionalAuthority disclosurePolicyAdministration() {
    return new FunctionalAuthority(
        "AnalyticsDisclosurePolicyAdministration", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }

  public static FunctionalAuthority disclosureRelease() {
    return new FunctionalAuthority(
        "AnalyticsDisclosureRelease", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }

  public static FunctionalAuthority disclosureReservationLifecycle() {
    return new FunctionalAuthority(
        "AnalyticsDisclosureReservationLifecycle", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }

  public static FunctionalAuthority dhis2MappingAdministration() {
    return new FunctionalAuthority(
        "AnalyticsDhis2MappingAdministration", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }

  public static FunctionalAuthority dhis2Export() {
    return new FunctionalAuthority(
        "AnalyticsDhis2Export", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }

  public static FunctionalAuthority dhis2ExportReconciliation() {
    return new FunctionalAuthority(
        "AnalyticsDhis2ExportReconciliation", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }

  public static FunctionalAuthority federatedAggregateMappingAdministration() {
    return new FunctionalAuthority(
        "AnalyticsFederatedAggregateMappingAdministration",
        Set.of(ExecutionScope.Kind.ORGANIZATION));
  }

  public static FunctionalAuthority federatedAggregatePublication() {
    return new FunctionalAuthority(
        "AnalyticsFederatedAggregatePublication", Set.of(ExecutionScope.Kind.ORGANIZATION));
  }
}
