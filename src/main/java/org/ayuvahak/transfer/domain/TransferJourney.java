package org.ayuvahak.transfer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Transfer-owned coordination history for one Referral, without copied patient context. */
public record TransferJourney(
    InternalId id,
    InternalId referralId,
    ExternalReference originOrganization,
    Optional<ExternalReference> originFacility,
    ActorReference createdBy,
    Instant createdAt,
    long version) {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  public TransferJourney {
    Objects.requireNonNull(id, "journey id");
    Objects.requireNonNull(referralId, "Referral id");
    requireDirectoryReference(originOrganization, "Organization");
    originFacility = Objects.requireNonNull(originFacility, "origin Facility");
    originFacility.ifPresent(reference -> requireDirectoryReference(reference, "Facility"));
    Objects.requireNonNull(createdBy, "creator");
    Objects.requireNonNull(createdAt, "creation time");
    if (version < 1) {
      throw new IllegalArgumentException("journey version must be positive");
    }
  }

  private static void requireDirectoryReference(ExternalReference reference, String type) {
    Objects.requireNonNull(reference, type + " reference");
    if (!DIRECTORY_ISSUER.equals(reference.issuer().value())
        || !type.equals(reference.resourceType())) {
      throw new IllegalArgumentException("journey origin must use a Directory reference");
    }
    InternalId.parse(reference.value());
  }
}
