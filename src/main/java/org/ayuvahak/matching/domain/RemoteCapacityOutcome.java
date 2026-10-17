package org.ayuvahak.matching.domain;

/** Consumer-owned outcome for one remote source observation. */
public enum RemoteCapacityOutcome {
  READY,
  NOT_READY,
  CONFLICTED,
  STALE,
  UNKNOWN
}
