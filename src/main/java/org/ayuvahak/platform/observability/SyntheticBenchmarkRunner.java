package org.ayuvahak.platform.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Executes a bounded local synthetic workload and returns identifier-free capacity evidence. */
public final class SyntheticBenchmarkRunner {

  private static final int MAXIMUM_UNIQUE_PLAN_FINGERPRINTS = 100;

  private final LongSupplier nanoTime;
  private final Supplier<Instant> clock;

  public SyntheticBenchmarkRunner() {
    this(System::nanoTime, Instant::now);
  }

  SyntheticBenchmarkRunner(LongSupplier nanoTime, Supplier<Instant> clock) {
    this.nanoTime = Objects.requireNonNull(nanoTime, "monotonic clock is required");
    this.clock = Objects.requireNonNull(clock, "wall clock is required");
  }

  public BenchmarkRunResult run(
      BenchmarkDeclaration declaration, SyntheticBenchmarkWorkload workload) {
    Objects.requireNonNull(declaration, "benchmark declaration is required");
    Objects.requireNonNull(workload, "synthetic workload adapter is required");

    Instant startedAt = clock.get();
    long startedNanos = nanoTime.getAsLong();
    List<Long> latencies = new ArrayList<>();
    EnumMap<DurableWorkQueue, Duration> maximumBacklogAge = new EnumMap<>(DurableWorkQueue.class);
    Set<String> planFingerprints = new TreeSet<>();
    int warmupFailures = 0;
    int failedOperations = 0;
    double maximumCpu = 0.0;
    long peakMemoryBytes = 0;
    int maximumActiveConnections = 0;
    long queryCount = 0;

    for (int repetition = 0; repetition < declaration.repetitions(); repetition++) {
      for (int operation = 0;
          operation < declaration.warmupOperationsPerRepetition();
          operation++) {
        try {
          Objects.requireNonNull(
              workload.execute(
                  declaration.workload(), BenchmarkStage.WARMUP, repetition, operation),
              "benchmark warm-up returned no observation");
        } catch (Exception exception) {
          warmupFailures++;
        }
      }

      for (int operation = 0;
          operation < declaration.measuredOperationsPerRepetition();
          operation++) {
        long operationStarted = nanoTime.getAsLong();
        try {
          BenchmarkObservation observation =
              Objects.requireNonNull(
                  workload.execute(
                      declaration.workload(), BenchmarkStage.MEASURED, repetition, operation),
                  "measured benchmark operation returned no observation");
          maximumCpu = Math.max(maximumCpu, observation.processCpuUtilization());
          peakMemoryBytes = Math.max(peakMemoryBytes, observation.processMemoryBytes());
          maximumActiveConnections =
              Math.max(maximumActiveConnections, observation.activeDatabaseConnections());
          queryCount = Math.addExact(queryCount, observation.databaseQueryCount());
          observation
              .oldestBacklogAge()
              .forEach(
                  (queue, age) ->
                      maximumBacklogAge.merge(queue, age, SyntheticBenchmarkRunner::max));
          Set<String> updatedFingerprints = new TreeSet<>(planFingerprints);
          updatedFingerprints.addAll(observation.queryPlanFingerprints());
          if (updatedFingerprints.size() > MAXIMUM_UNIQUE_PLAN_FINGERPRINTS) {
            throw new IllegalStateException(
                "benchmark contains too many distinct query plan fingerprints");
          }
          planFingerprints.addAll(observation.queryPlanFingerprints());
        } catch (Exception exception) {
          failedOperations++;
        }
        latencies.add(Math.max(1L, nanoTime.getAsLong() - operationStarted));
      }
    }

    long elapsedNanos = Math.max(1L, nanoTime.getAsLong() - startedNanos);
    Instant completedAt = clock.get();
    latencies.sort(Long::compareTo);
    int measuredOperations = latencies.size();
    double p50 = percentileMilliseconds(latencies, 0.50);
    double p95 = percentileMilliseconds(latencies, 0.95);
    double p99 = percentileMilliseconds(latencies, 0.99);
    double throughput = measuredOperations * 1_000_000_000.0 / elapsedNanos;

    return new BenchmarkRunResult(
        declaration,
        startedAt,
        completedAt,
        Duration.ofNanos(elapsedNanos),
        warmupFailures,
        measuredOperations,
        failedOperations,
        p50,
        p95,
        p99,
        throughput,
        (double) failedOperations / measuredOperations,
        maximumCpu,
        peakMemoryBytes,
        maximumActiveConnections,
        queryCount,
        List.copyOf(planFingerprints),
        Map.copyOf(maximumBacklogAge));
  }

  private static Duration max(Duration left, Duration right) {
    return left.compareTo(right) >= 0 ? left : right;
  }

  private static double percentileMilliseconds(List<Long> sortedNanos, double percentile) {
    int rank = (int) Math.ceil(percentile * sortedNanos.size());
    return sortedNanos.get(Math.max(0, rank - 1)) / 1_000_000.0;
  }
}
