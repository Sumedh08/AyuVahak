package org.ayuvahak.directory.domain;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Coordination projection of an administrative or legal organization. */
public record Organization(
    InternalId id,
    String name,
    Optional<InternalId> parentOrganizationId,
    DirectorySource source,
    DirectoryProvenance provenance,
    DirectoryLifecycleStatus status,
    long version) {

  public Organization {
    Objects.requireNonNull(id, "id");
    requireName(name);
    parentOrganizationId = Objects.requireNonNull(parentOrganizationId, "parent organization");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(status, "status");
    if (version < 1) {
      throw new IllegalArgumentException("organization version must be positive");
    }
  }

  public Organization correct(String correctedName, DirectoryProvenance correction) {
    if (status != DirectoryLifecycleStatus.ACTIVE) {
      throw new IllegalStateException("inactive organization cannot be corrected");
    }
    return new Organization(
        id,
        correctedName,
        parentOrganizationId,
        source,
        correction,
        status,
        Math.addExact(version, 1));
  }

  private static void requireName(String value) {
    if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 200) {
      throw new IllegalArgumentException(
          "organization name must be trimmed and 1 to 200 characters");
    }
  }
}
