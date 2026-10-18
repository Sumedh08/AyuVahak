package org.ayuvahak.analytics.application;

/** Sanitized, bounded counts from one synchronous DHIS2 aggregate import response. */
public record Dhis2ImportSummary(
    Dhis2ImportOutcome outcome, int imported, int updated, int ignored, int deleted) {

  public Dhis2ImportSummary {
    if (outcome == null
        || imported < 0
        || updated < 0
        || ignored < 0
        || deleted < 0
        || imported > 1
        || updated > 1
        || ignored > 1
        || deleted > 1) {
      throw new IllegalArgumentException("DHIS2 import summary is outside the one-value bound");
    }
  }

  @Override
  public String toString() {
    return "Dhis2ImportSummary[outcome="
        + outcome
        + ", imported="
        + imported
        + ", updated="
        + updated
        + ", ignored="
        + ignored
        + ", deleted="
        + deleted
        + "]";
  }
}
