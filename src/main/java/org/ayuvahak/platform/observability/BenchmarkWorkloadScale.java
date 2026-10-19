package org.ayuvahak.platform.observability;

/** Numeric synthetic workload dimensions; contains no business-resource identifiers. */
public record BenchmarkWorkloadScale(
    int organizations,
    int facilities,
    int activeReferrals,
    int transfersPerDay,
    int capacityObservationsPerMinute,
    int concurrentSseClients,
    int federationPeers,
    int analyticsIndicators,
    int reportingPeriods,
    int exportOperations) {

  public BenchmarkWorkloadScale {
    requireNonNegative("organizations", organizations);
    requireNonNegative("facilities", facilities);
    requireNonNegative("active referrals", activeReferrals);
    requireNonNegative("transfers per day", transfersPerDay);
    requireNonNegative("capacity observations per minute", capacityObservationsPerMinute);
    requireNonNegative("concurrent SSE clients", concurrentSseClients);
    requireNonNegative("federation peers", federationPeers);
    requireNonNegative("Analytics indicators", analyticsIndicators);
    requireNonNegative("reporting periods", reportingPeriods);
    requireNonNegative("export operations", exportOperations);
  }

  private static void requireNonNegative(String field, int value) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
  }
}
