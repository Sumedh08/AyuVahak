package org.ayuvahak.platform.observability;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Per-operation resource evidence returned by a local synthetic workload adapter. */
public record BenchmarkObservation(
    int activeDatabaseConnections,
    long databaseQueryCount,
    double processCpuUtilization,
    long processMemoryBytes,
    Map<DurableWorkQueue, Duration> oldestBacklogAge,
    Set<String> queryPlanFingerprints) {

  private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");
  private static final int MAXIMUM_PLAN_FINGERPRINTS = 100;

  public BenchmarkObservation {
    if (activeDatabaseConnections < 0
        || databaseQueryCount < 0
        || processMemoryBytes < 0
        || !Double.isFinite(processCpuUtilization)
        || processCpuUtilization < 0.0
        || processCpuUtilization > 1.0) {
      throw new IllegalArgumentException(
          "benchmark resource measurements are outside valid bounds");
    }
    oldestBacklogAge = oldestBacklogAge == null ? Map.of() : Map.copyOf(oldestBacklogAge);
    if (oldestBacklogAge.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey() == null
                    || entry.getValue() == null
                    || entry.getValue().isNegative())) {
      throw new IllegalArgumentException(
          "backlog ages require a bounded queue and non-negative age");
    }
    queryPlanFingerprints =
        queryPlanFingerprints == null ? Set.of() : Set.copyOf(queryPlanFingerprints);
    if (queryPlanFingerprints.size() > MAXIMUM_PLAN_FINGERPRINTS
        || queryPlanFingerprints.stream().anyMatch(value -> !SHA_256.matcher(value).matches())) {
      throw new IllegalArgumentException(
          "query plans must be represented by at most 100 SHA-256 fingerprints");
    }
  }
}
