package org.ayuvahak.capacity.domain;

/** Deterministic Capacity evidence outcome; it is not a receiving authority's decision. */
public enum CapacityReadinessOutcome {
  READY,
  NOT_READY,
  CONFLICTED,
  STALE,
  UNKNOWN
}
