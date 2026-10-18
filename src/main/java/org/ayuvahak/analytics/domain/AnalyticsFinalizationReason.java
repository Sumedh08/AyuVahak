package org.ayuvahak.analytics.domain;

/** Bounded reasons for explicitly closing a provisional reporting period. */
public enum AnalyticsFinalizationReason {
  PERIOD_CLOSED,
  CORRECTION_WINDOW_ELAPSED,
  OPERATOR_REQUEST
}
