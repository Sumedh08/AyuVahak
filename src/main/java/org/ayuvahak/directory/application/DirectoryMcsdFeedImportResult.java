package org.ayuvahak.directory.application;

import java.util.List;
import java.util.Objects;

/** Minimized owner outcome for a feed message; it contains no source payload or patient data. */
public record DirectoryMcsdFeedImportResult(
    List<DirectorySourceProjectionReconciliation.Outcome> outcomes, boolean replayed) {

  public DirectoryMcsdFeedImportResult {
    outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
    if (outcomes.isEmpty()) {
      throw new IllegalArgumentException("a completed Directory feed must have an entry outcome");
    }
  }
}
