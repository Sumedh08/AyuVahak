package org.ayuvahak.analytics.application;

/** Narrow interpretation of the documented single-value import summary. */
public enum Dhis2ImportOutcome {
  IMPORTED_ACKNOWLEDGED,
  UPDATED_ACKNOWLEDGED,
  IGNORED_AMBIGUOUS,
  UNSUPPORTED_ASYNC
}
