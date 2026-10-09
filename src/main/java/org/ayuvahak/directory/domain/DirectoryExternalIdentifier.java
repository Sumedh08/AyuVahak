package org.ayuvahak.directory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Directory's association of an external identifier with one Directory-owned concept. */
public record DirectoryExternalIdentifier(
    InternalId id,
    DirectoryConceptType conceptType,
    InternalId conceptId,
    ExternalReference externalIdentifier,
    ExternalIdentifierStatus status,
    Optional<InternalId> supersedesIdentifierId,
    DirectoryProvenance provenance,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public DirectoryExternalIdentifier {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(conceptType, "conceptType");
    Objects.requireNonNull(conceptId, "conceptId");
    Objects.requireNonNull(externalIdentifier, "externalIdentifier");
    Objects.requireNonNull(status, "status");
    supersedesIdentifierId =
        Objects.requireNonNull(supersedesIdentifierId, "supersedesIdentifierId");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < 1) {
      throw new IllegalArgumentException("external identifier version must be positive");
    }
    if (createdAt.isAfter(updatedAt)) {
      throw new IllegalArgumentException("external identifier creation cannot follow update");
    }
    if (supersedesIdentifierId.filter(id::equals).isPresent()) {
      throw new IllegalArgumentException("an external identifier cannot supersede itself");
    }
  }

  public DirectoryExternalIdentifier transition(
      ExternalIdentifierStatus nextStatus,
      DirectoryProvenance transitionProvenance,
      Instant changedAt) {
    if (status != ExternalIdentifierStatus.ACTIVE) {
      throw new IllegalStateException("only an active external identifier can be changed");
    }
    if (nextStatus == ExternalIdentifierStatus.ACTIVE) {
      throw new IllegalArgumentException(
          "an identifier transition must close the active association");
    }
    return new DirectoryExternalIdentifier(
        id,
        conceptType,
        conceptId,
        externalIdentifier,
        nextStatus,
        supersedesIdentifierId,
        transitionProvenance,
        Math.addExact(version, 1),
        createdAt,
        changedAt);
  }
}
