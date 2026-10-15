package org.ayuvahak.directory.application;

import java.util.Objects;
import java.util.Optional;

/** Minimized outcome of a bounded Directory source synchronization run. */
public record DirectoryMcsdSynchronizationResult(
    Status status, int pagesRead, int factsImported, Optional<String> sourceCursor) {

  public DirectoryMcsdSynchronizationResult {
    Objects.requireNonNull(status, "status");
    sourceCursor = Objects.requireNonNull(sourceCursor, "sourceCursor");
    if (pagesRead < 0 || factsImported < 0) {
      throw new IllegalArgumentException("synchronization counts cannot be negative");
    }
  }

  public enum Status {
    COMPLETE,
    INCOMPLETE,
    SOURCE_UNAVAILABLE,
    REJECTED,
    NO_CHECKPOINT
  }
}
