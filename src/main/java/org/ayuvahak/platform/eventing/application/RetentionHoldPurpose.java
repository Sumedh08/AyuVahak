package org.ayuvahak.platform.eventing.application;

/** Bounded purposes for pausing owner-controlled outbox retention. */
public enum RetentionHoldPurpose {
  LEGAL_REVIEW,
  SAFETY_INVESTIGATION,
  UNRESOLVED_RECONCILIATION
}
