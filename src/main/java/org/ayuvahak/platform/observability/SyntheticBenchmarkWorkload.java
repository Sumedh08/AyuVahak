package org.ayuvahak.platform.observability;

/** Adapter for a repeatable local synthetic scenario; it must not target external systems. */
@FunctionalInterface
public interface SyntheticBenchmarkWorkload {

  BenchmarkObservation execute(
      BenchmarkWorkloadScale scale, BenchmarkStage stage, int repetition, int operationIndex)
      throws Exception;
}
