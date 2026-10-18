package org.ayuvahak.analytics.domain;

/** Durable release lifecycle; reserved/unknown states remain part of disclosure history. */
public enum DisclosureReservationState {
  RESERVED,
  DISCLOSED,
  POTENTIALLY_DISCLOSED,
  WITHDRAWN_NOT_SENT,
  WITHHELD,
  REVIEW_REQUIRED
}
