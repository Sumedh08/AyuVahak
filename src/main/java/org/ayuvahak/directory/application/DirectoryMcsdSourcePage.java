package org.ayuvahak.directory.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A bounded page of owner-mapped Directory facts from one configured mCSD source. */
public record DirectoryMcsdSourcePage(
    List<DirectorySourceProjection> projections,
    Optional<String> nextPageToken,
    Optional<String> nextCursor,
    boolean complete) {

  public DirectoryMcsdSourcePage {
    projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
    nextPageToken = Objects.requireNonNull(nextPageToken, "nextPageToken");
    nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    nextPageToken.ifPresent(
        token -> {
          if (token.isBlank() || !token.equals(token.strip()) || token.length() > 2048) {
            throw new IllegalArgumentException("mCSD page token is invalid");
          }
        });
    nextCursor.ifPresent(
        cursor -> {
          if (cursor.isBlank() || !cursor.equals(cursor.strip()) || cursor.length() > 2048) {
            throw new IllegalArgumentException("mCSD source cursor is invalid");
          }
        });
    if (complete && nextPageToken.isPresent()) {
      throw new IllegalArgumentException("a complete mCSD read cannot have a next page token");
    }
    if (!complete && nextCursor.isPresent()) {
      throw new IllegalArgumentException("an incomplete mCSD read cannot advance its cursor");
    }
  }
}
