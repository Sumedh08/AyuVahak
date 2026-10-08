package org.ayuvahak.platform.eventing.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record EventDeliveryPage(List<EventDeliverySummary> items, Optional<String> nextCursor) {
  public EventDeliveryPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
  }
}
