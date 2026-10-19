package org.ayuvahak.platform.eventing.application;

/** Bounded release reasons retained with a platform outbox hold. */
public enum RetentionHoldReleaseReason {
  REVIEW_COMPLETE,
  INVESTIGATION_CLOSED,
  RECONCILIATION_COMPLETE
}
