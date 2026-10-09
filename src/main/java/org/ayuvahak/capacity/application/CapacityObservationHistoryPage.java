package org.ayuvahak.capacity.application;

import java.util.List;
import java.util.Objects;
import org.ayuvahak.capacity.domain.CapacityObservationFact;

/** Deterministically ordered, bounded observation-history page. */
public record CapacityObservationHistoryPage(
    List<CapacityObservationFact> items, int page, int size, boolean hasMore) {

  public CapacityObservationHistoryPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    if (page < 0 || size < 1 || items.size() > size) {
      throw new IllegalArgumentException("history page bounds are invalid");
    }
  }
}
