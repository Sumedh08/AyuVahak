package org.ayuvahak.platform.audit.application;

import java.util.List;
import java.util.Optional;

/** One bounded page of minimized audit evidence. */
public record AuditRecordPage(List<AuditRecordView> items, Optional<String> nextCursor) {

  public AuditRecordPage {
    items = List.copyOf(items);
    nextCursor = java.util.Objects.requireNonNull(nextCursor, "nextCursor");
  }
}
