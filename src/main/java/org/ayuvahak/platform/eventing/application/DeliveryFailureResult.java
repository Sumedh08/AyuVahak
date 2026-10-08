package org.ayuvahak.platform.eventing.application;

/** Outcome of recording a handler failure under its current fencing token. */
public enum DeliveryFailureResult {
  RETRY_SCHEDULED,
  STALLED,
  LEASE_LOST
}
