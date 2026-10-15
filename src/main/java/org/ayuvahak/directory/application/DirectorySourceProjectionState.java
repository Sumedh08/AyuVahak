package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryConceptType;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Durable, bounded source revision metadata for one Directory-owned projection. */
public record DirectorySourceProjectionState(
    InternalId projectionId,
    ExternalReference sourceIdentity,
    DirectoryConceptType conceptType,
    Optional<InternalId> conceptId,
    String profileUri,
    Optional<String> opaqueVersionId,
    Optional<Instant> sourceUpdatedAt,
    boolean sourceUpdatedAtIsOrderedRevision,
    String projectionFingerprint,
    boolean sourceActive,
    DirectorySourceProjectionReconciliation.Outcome lastOutcome,
    long version,
    Instant recordedAt) {

  public DirectorySourceProjectionState {
    Objects.requireNonNull(projectionId, "projectionId");
    Objects.requireNonNull(sourceIdentity, "sourceIdentity");
    Objects.requireNonNull(conceptType, "conceptType");
    conceptId = Objects.requireNonNull(conceptId, "conceptId");
    if (profileUri == null || profileUri.isBlank()) {
      throw new IllegalArgumentException("source profile URI is required");
    }
    opaqueVersionId = Objects.requireNonNull(opaqueVersionId, "opaqueVersionId");
    sourceUpdatedAt = Objects.requireNonNull(sourceUpdatedAt, "sourceUpdatedAt");
    if (projectionFingerprint == null || !projectionFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("projection fingerprint must be SHA-256 hex");
    }
    Objects.requireNonNull(lastOutcome, "lastOutcome");
    if (version < 1) {
      throw new IllegalArgumentException("projection state version must be positive");
    }
    Objects.requireNonNull(recordedAt, "recordedAt");
  }
}
