package org.ayuvahak.analytics.domain;

/** Local delivery lifecycle; an unknown result remains a barrier for later correction work. */
public enum Dhis2ExportState {
  PREPARED,
  SENDING,
  ACKNOWLEDGED,
  OUTCOME_UNKNOWN,
  RECONCILIATION_REQUIRED,
  REJECTED,
  WITHHELD
}
