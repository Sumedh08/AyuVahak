package org.ayuvahak.analytics.application;

import java.util.List;

/** Bounded page of aggregate snapshot summaries. */
public record AnalyticsSnapshotPage(
    List<AnalyticsSnapshotSummary> items, int page, int size, boolean hasMore) {
  public AnalyticsSnapshotPage {
    items = List.copyOf(items);
  }
}
