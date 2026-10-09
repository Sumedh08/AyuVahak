package org.ayuvahak.directory.application;

import java.util.List;
import java.util.Objects;

/** Bounded page of current external-identifier assertions or their correction history. */
public record DirectoryExternalIdentifierPage(
    List<DirectoryExternalIdentifierHistoryEntry> items, int page, int size, boolean hasMore) {

  public DirectoryExternalIdentifierPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    if (page < 0 || size < 1) {
      throw new IllegalArgumentException("external identifier page bounds must be nonnegative");
    }
  }
}
