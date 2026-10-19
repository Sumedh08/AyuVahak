package org.ayuvahak.platform.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Sanitized summary of one repeatable benchmark execution. */
public record BenchmarkRunResult(
    BenchmarkDeclaration declaration,
    Instant startedAt,
    Instant completedAt,
    Duration elapsed,
    int warmupFailures,
    int measuredOperations,
    int failedOperations,
    double p50Milliseconds,
    double p95Milliseconds,
    double p99Milliseconds,
    double throughputOperationsPerSecond,
    double errorRate,
    double maximumProcessCpuUtilization,
    long peakProcessMemoryBytes,
    int maximumActiveDatabaseConnections,
    long databaseQueryCount,
    List<String> queryPlanFingerprints,
    Map<DurableWorkQueue, Duration> maximumBacklogAge) {

  public BenchmarkRunResult {
    if (declaration == null || startedAt == null || completedAt == null || elapsed == null) {
      throw new IllegalArgumentException("benchmark result requires declaration and time bounds");
    }
    if (elapsed.isNegative()
        || elapsed.isZero()
        || warmupFailures < 0
        || measuredOperations <= 0
        || failedOperations < 0
        || failedOperations > measuredOperations
        || maximumProcessCpuUtilization < 0.0
        || maximumProcessCpuUtilization > 1.0
        || !Double.isFinite(maximumProcessCpuUtilization)
        || peakProcessMemoryBytes < 0
        || maximumActiveDatabaseConnections < 0
        || databaseQueryCount < 0) {
      throw new IllegalArgumentException("benchmark result measurements are outside valid bounds");
    }
    if (p50Milliseconds < 0.0
        || p50Milliseconds > p95Milliseconds
        || p95Milliseconds > p99Milliseconds
        || !Double.isFinite(p50Milliseconds)
        || !Double.isFinite(p95Milliseconds)
        || !Double.isFinite(p99Milliseconds)
        || throughputOperationsPerSecond <= 0.0
        || !Double.isFinite(throughputOperationsPerSecond)
        || errorRate < 0.0
        || errorRate > 1.0
        || !Double.isFinite(errorRate)) {
      throw new IllegalArgumentException("benchmark result statistics are outside valid bounds");
    }
    int expectedOperations =
        declaration.measuredOperationsPerRepetition() * declaration.repetitions();
    int expectedWarmupFailures =
        declaration.warmupOperationsPerRepetition() * declaration.repetitions();
    if (measuredOperations != expectedOperations || warmupFailures > expectedWarmupFailures) {
      throw new IllegalArgumentException("benchmark result counts do not match its declaration");
    }
    queryPlanFingerprints = List.copyOf(queryPlanFingerprints);
    if (queryPlanFingerprints.size() > 100
        || queryPlanFingerprints.stream().distinct().count() != queryPlanFingerprints.size()
        || queryPlanFingerprints.stream()
            .anyMatch(value -> !Pattern.matches("[0-9a-fA-F]{64}", value))) {
      throw new IllegalArgumentException(
          "query plan evidence must contain at most 100 SHA-256 fingerprints");
    }
    maximumBacklogAge = Map.copyOf(maximumBacklogAge);
    if (maximumBacklogAge.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey() == null
                    || entry.getValue() == null
                    || entry.getValue().isNegative())) {
      throw new IllegalArgumentException(
          "result backlog ages must be non-negative bounded queue values");
    }
  }

  public Set<DurableWorkQueue> queuesWithBacklogEvidence() {
    return maximumBacklogAge.keySet();
  }
}
