package org.ayuvahak.directory.application;

import java.util.Objects;
import java.util.Optional;

/** One mapped feed fact with its transaction-response position. */
public record DirectoryMcsdFeedEntry(
    int position,
    Optional<DirectorySourceProjection> projection,
    Optional<DirectoryMcsdSourceDeletion> deletion) {

  public DirectoryMcsdFeedEntry(int position, DirectorySourceProjection projection) {
    this(position, Optional.of(projection), Optional.empty());
  }

  public static DirectoryMcsdFeedEntry deletion(
      int position, DirectoryMcsdSourceDeletion deletion) {
    return new DirectoryMcsdFeedEntry(position, Optional.empty(), Optional.of(deletion));
  }

  public DirectoryMcsdFeedEntry {
    if (position < 0) {
      throw new IllegalArgumentException("feed entry position must be nonnegative");
    }
    projection = Objects.requireNonNull(projection, "projection");
    deletion = Objects.requireNonNull(deletion, "deletion");
    if (projection.isPresent() == deletion.isPresent()) {
      throw new IllegalArgumentException("feed entry must contain exactly one source change");
    }
  }
}
