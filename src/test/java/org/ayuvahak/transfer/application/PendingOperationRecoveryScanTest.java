package org.ayuvahak.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.InternalId;
import org.junit.jupiter.api.Test;

class PendingOperationRecoveryScanTest {

  @Test
  void advancesThroughPagesAndWrapsWithoutStarvingLaterOperations() {
    List<InternalId> pending = List.of(id(1), id(2), id(3), id(4), id(5));
    PendingOperationRecoveryScan scan = new PendingOperationRecoveryScan();

    assertThat(scan.next(2, (limit, after) -> page(pending, limit, after)))
        .containsExactly(id(1), id(2));
    assertThat(scan.next(2, (limit, after) -> page(pending, limit, after)))
        .containsExactly(id(3), id(4));
    assertThat(scan.next(2, (limit, after) -> page(pending, limit, after))).containsExactly(id(5));
    assertThat(scan.next(2, (limit, after) -> page(pending, limit, after)))
        .containsExactly(id(1), id(2));
  }

  private static List<InternalId> page(
      List<InternalId> pending, int limit, Optional<InternalId> after) {
    return pending.stream()
        .filter(
            id -> after.isEmpty() || id.toString().compareTo(after.orElseThrow().toString()) > 0)
        .limit(limit)
        .toList();
  }

  private static InternalId id(int value) {
    return new InternalId(UUID.fromString("00000000-0000-4000-8000-%012d".formatted(value)));
  }
}
