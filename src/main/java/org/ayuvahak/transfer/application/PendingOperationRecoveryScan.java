package org.ayuvahak.transfer.application;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import org.ayuvahak.platform.core.InternalId;

/** Cursor for bounded recovery scans of pending cross-context outcomes and releases. */
final class PendingOperationRecoveryScan {

  private Optional<InternalId> after = Optional.empty();

  synchronized List<InternalId> next(
      int limit,
      BiFunction<Integer, Optional<InternalId>, List<InternalId>> pendingOperationQuery) {
    List<InternalId> page = pendingOperationQuery.apply(limit, after);
    if (page.isEmpty() && after.isPresent()) {
      after = Optional.empty();
      page = pendingOperationQuery.apply(limit, after);
    }
    after = page.isEmpty() ? Optional.empty() : Optional.of(page.get(page.size() - 1));
    return List.copyOf(page);
  }
}
