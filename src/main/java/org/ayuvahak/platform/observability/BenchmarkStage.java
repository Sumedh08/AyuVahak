package org.ayuvahak.platform.observability;

/** Separates warm-up operations from measured operations in benchmark adapters. */
public enum BenchmarkStage {
  WARMUP,
  MEASURED
}
