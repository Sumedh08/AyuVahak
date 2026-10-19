package org.ayuvahak.platform.observability;

/** Bounded queue categories permitted in capacity evidence. */
public enum DurableWorkQueue {
  OUTBOX,
  NOTIFICATION,
  RECONCILIATION,
  FEDERATION,
  EXCHANGE,
  ANALYTICS,
  DHIS2
}
