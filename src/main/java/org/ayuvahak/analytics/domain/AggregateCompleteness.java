package org.ayuvahak.analytics.domain;

/** Completeness of the required owner-produced inputs for one aggregate computation. */
public enum AggregateCompleteness {
  COMPLETE,
  PARTIAL,
  MISSING_SOURCE,
  STALE_SOURCE,
  NOT_APPLICABLE
}
