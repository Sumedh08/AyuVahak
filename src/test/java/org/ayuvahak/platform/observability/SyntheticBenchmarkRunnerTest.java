package org.ayuvahak.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SyntheticBenchmarkRunnerTest {

  @Test
  void producesRepeatableBoundedEvidenceWithoutRetainingOperationIdentifiersOrErrors() {
    AtomicLong nanos = new AtomicLong();
    AtomicInteger clockCalls = new AtomicInteger();
    AtomicInteger warmupCalls = new AtomicInteger();
    BenchmarkDeclaration declaration = declaration();
    SyntheticBenchmarkRunner runner =
        new SyntheticBenchmarkRunner(
            () -> nanos.getAndAdd(Duration.ofMillis(10).toNanos()),
            () ->
                clockCalls.getAndIncrement() == 0
                    ? Instant.parse("2026-10-20T00:00:00Z")
                    : Instant.parse("2026-10-20T00:01:00Z"));

    BenchmarkRunResult result =
        runner.run(
            declaration,
            (scale, stage, repetition, operationIndex) -> {
              assertThat(scale).isEqualTo(declaration.workload());
              if (stage == BenchmarkStage.WARMUP) {
                warmupCalls.incrementAndGet();
              } else if (repetition == 1 && operationIndex == 1) {
                throw new IllegalStateException("SYN-PATIENT-PRIVATE-91F4");
              }
              return observation();
            });

    assertThat(warmupCalls).hasValue(4);
    assertThat(result.declaration()).isEqualTo(declaration);
    assertThat(result.startedAt()).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
    assertThat(result.completedAt()).isEqualTo(Instant.parse("2026-10-20T00:01:00Z"));
    assertThat(result.measuredOperations()).isEqualTo(6);
    assertThat(result.failedOperations()).isEqualTo(1);
    assertThat(result.errorRate())
        .isCloseTo(1.0 / 6.0, org.assertj.core.data.Offset.offset(0.00001));
    assertThat(result.p50Milliseconds()).isEqualTo(10.0);
    assertThat(result.p95Milliseconds()).isEqualTo(10.0);
    assertThat(result.p99Milliseconds()).isEqualTo(10.0);
    assertThat(result.throughputOperationsPerSecond()).isPositive();
    assertThat(result.maximumProcessCpuUtilization()).isEqualTo(0.5);
    assertThat(result.peakProcessMemoryBytes()).isEqualTo(2_097_152);
    assertThat(result.maximumActiveDatabaseConnections()).isEqualTo(4);
    assertThat(result.databaseQueryCount()).isEqualTo(20);
    assertThat(result.queryPlanFingerprints()).containsExactly("a".repeat(64));
    assertThat(result.maximumBacklogAge())
        .containsEntry(DurableWorkQueue.OUTBOX, Duration.ofSeconds(3));
    assertThat(result.toString()).doesNotContain("SYN-PATIENT-PRIVATE-91F4");
  }

  @Test
  void boundsSyntheticInputsAndRejectsIdentifierLikeQueryPlanPayloads() {
    assertThatThrownBy(() -> new BenchmarkWorkloadScale(1, 1, -1, 0, 0, 0, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("active referrals");

    assertThatThrownBy(
            () -> new BenchmarkObservation(1, 1, 0.1, 1, Map.of(), Set.of("SYN-TRANSFER-001")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SHA-256 fingerprints");
  }

  private static BenchmarkDeclaration declaration() {
    return new BenchmarkDeclaration(
        "a1b2c3d",
        "Eclipse Temurin 25.0.4.1+1",
        "PostgreSQL 18 synthetic local",
        8,
        16_384,
        new BenchmarkWorkloadScale(2, 8, 100, 20, 30, 4, 2, 3, 12, 10),
        BenchmarkRunCondition.WARM,
        2,
        3,
        2);
  }

  private static BenchmarkObservation observation() {
    return new BenchmarkObservation(
        4,
        4,
        0.5,
        2_097_152,
        Map.of(DurableWorkQueue.OUTBOX, Duration.ofSeconds(3)),
        Set.of("a".repeat(64)));
  }
}
