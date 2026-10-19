package org.ayuvahak.platform.observability;

import java.util.Objects;
import java.util.regex.Pattern;

/** Repeatable synthetic benchmark inputs and runtime context. */
public record BenchmarkDeclaration(
    String sourceRevision,
    String javaRuntime,
    String databaseVersion,
    int cpuCores,
    long memoryMiB,
    BenchmarkWorkloadScale workload,
    BenchmarkRunCondition condition,
    int warmupOperationsPerRepetition,
    int measuredOperationsPerRepetition,
    int repetitions) {

  private static final Pattern REVISION = Pattern.compile("[0-9a-fA-F]{7,64}");
  private static final int MAXIMUM_REPETITIONS = 100;
  private static final int MAXIMUM_TOTAL_OPERATIONS = 1_000_000;

  public BenchmarkDeclaration {
    if (sourceRevision == null || !REVISION.matcher(sourceRevision).matches()) {
      throw new IllegalArgumentException("source revision must be a 7 to 64 character Git hash");
    }
    javaRuntime = ServiceLevelOmission.boundedText(javaRuntime, "Java runtime", 80);
    databaseVersion = ServiceLevelOmission.boundedText(databaseVersion, "database version", 80);
    if (cpuCores <= 0 || memoryMiB <= 0) {
      throw new IllegalArgumentException("CPU core and memory environment values must be positive");
    }
    Objects.requireNonNull(workload, "synthetic workload dimensions are required");
    Objects.requireNonNull(condition, "run condition is required");
    if (warmupOperationsPerRepetition < 0
        || measuredOperationsPerRepetition <= 0
        || repetitions <= 0
        || repetitions > MAXIMUM_REPETITIONS) {
      throw new IllegalArgumentException("benchmark operation and repetition counts are invalid");
    }
    long total =
        (long) repetitions
            * (warmupOperationsPerRepetition + (long) measuredOperationsPerRepetition);
    if (total > MAXIMUM_TOTAL_OPERATIONS) {
      throw new IllegalArgumentException("benchmark exceeds the bounded total operation count");
    }
  }
}
