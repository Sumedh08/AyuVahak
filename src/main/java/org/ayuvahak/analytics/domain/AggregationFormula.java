package org.ayuvahak.analytics.domain;

/** Small, deterministic formula vocabulary supported by versioned Analytics definitions. */
public enum AggregationFormula {
  SUM,

  /** Sum numerator and denominator measures, then divide to six places using HALF_EVEN. */
  RATIO_6DP_HALF_EVEN
}
