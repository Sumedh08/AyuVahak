package org.ayuvahak.platform.observability;

/** Fixed deployment service categories; arbitrary identifiers are not accepted as a scope. */
public enum ServiceScope {
  API,
  DATABASE,
  AUDIT,
  OUTBOX,
  NOTIFICATION,
  RECONCILIATION,
  FEDERATION,
  EXCHANGE,
  SSE,
  ANALYTICS,
  DHIS2,
  PROCESS
}
