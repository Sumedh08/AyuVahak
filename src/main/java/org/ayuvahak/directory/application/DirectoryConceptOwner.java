package org.ayuvahak.directory.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Owner scope resolved by Directory before it authorizes a concept mutation or read. */
public record DirectoryConceptOwner(
    Optional<InternalId> organizationId, Optional<InternalId> facilityId, boolean active) {

  public DirectoryConceptOwner {
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    facilityId = Objects.requireNonNull(facilityId, "facilityId");
    if (facilityId.isPresent() && organizationId.isEmpty()) {
      throw new IllegalArgumentException(
          "a facility-owned concept must have an organization owner");
    }
  }
}
