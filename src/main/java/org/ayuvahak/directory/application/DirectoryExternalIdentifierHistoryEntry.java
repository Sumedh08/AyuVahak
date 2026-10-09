package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryExternalIdentifier;

/** Bounded projection of a current external-identifier state or one superseded state. */
public record DirectoryExternalIdentifierHistoryEntry(
    DirectoryExternalIdentifier identifier,
    boolean current,
    Instant recordedAt,
    Optional<String> changeReasonCode) {

  public DirectoryExternalIdentifierHistoryEntry {
    Objects.requireNonNull(identifier, "identifier");
    Objects.requireNonNull(recordedAt, "recordedAt");
    changeReasonCode = Objects.requireNonNull(changeReasonCode, "changeReasonCode");
  }
}
