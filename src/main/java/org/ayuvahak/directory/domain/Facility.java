package org.ayuvahak.directory.domain;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;

/** Coordination projection of a care-delivery site linked to its responsible organization. */
public record Facility(
    InternalId id,
    InternalId managingOrganizationId,
    String name,
    DirectorySource source,
    DirectoryProvenance provenance,
    DirectoryLifecycleStatus status,
    long version) {

  public Facility {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(managingOrganizationId, "managingOrganizationId");
    requireName(name);
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(status, "status");
    if (version < 1) {
      throw new IllegalArgumentException("facility version must be positive");
    }
  }

  public Facility correct(String correctedName, DirectoryProvenance correction) {
    if (status != DirectoryLifecycleStatus.ACTIVE) {
      throw new IllegalStateException("inactive facility cannot be corrected");
    }
    return new Facility(
        id,
        managingOrganizationId,
        correctedName,
        source,
        correction,
        status,
        Math.addExact(version, 1));
  }

  public Facility deactivate(DirectoryProvenance reason) {
    if (status != DirectoryLifecycleStatus.ACTIVE) {
      throw new IllegalStateException("inactive facility cannot be deactivated again");
    }
    return new Facility(
        id,
        managingOrganizationId,
        name,
        source,
        reason,
        DirectoryLifecycleStatus.INACTIVE,
        Math.addExact(version, 1));
  }

  private static void requireName(String value) {
    if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 200) {
      throw new IllegalArgumentException("facility name must be trimmed and 1 to 200 characters");
    }
  }
}
