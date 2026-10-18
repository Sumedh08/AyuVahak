package org.ayuvahak.analytics.domain;

/** Bounded, non-free-text causes for a new immutable aggregate revision. */
public enum AnalyticsRevisionCause {
  INITIAL,
  LATE_OWNER_FACT,
  OWNER_RESTATEMENT,
  SOURCE_DATA_ERROR,
  CALCULATION_ERROR
}
