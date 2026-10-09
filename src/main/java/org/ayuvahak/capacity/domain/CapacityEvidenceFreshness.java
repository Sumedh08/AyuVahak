package org.ayuvahak.capacity.domain;

/** Query-time usability of the selected source evidence. */
public enum CapacityEvidenceFreshness {
  CURRENT,
  STALE,
  EXPIRED,
  UNKNOWN,
  CONFLICTED
}
