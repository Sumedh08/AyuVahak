package org.ayuvahak.directory.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryConceptType;
import org.ayuvahak.platform.core.InternalId;

/** Minimized result of reconciling one configured source projection into Directory. */
public record DirectorySourceProjectionImportResult(
    DirectorySourceProjectionReconciliation.Outcome outcome,
    Optional<DirectoryConceptType> conceptType,
    Optional<InternalId> conceptId) {

  public DirectorySourceProjectionImportResult {
    Objects.requireNonNull(outcome, "outcome");
    conceptType = Objects.requireNonNull(conceptType, "conceptType");
    conceptId = Objects.requireNonNull(conceptId, "conceptId");
    if (conceptId.isPresent() != conceptType.isPresent()) {
      throw new IllegalArgumentException("a Directory concept reference requires both type and ID");
    }
  }
}
