package org.ayuvahak.directory.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.InternalId;

/** Deployment-governed CareCapability code; its optional parent does not imply inheritance. */
public record CareCapability(
    InternalId id,
    String code,
    String displayName,
    Optional<InternalId> parentCapabilityId,
    DirectorySource source,
    DirectoryProvenance provenance,
    DirectoryLifecycleStatus status,
    long version) {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9._-]{0,63}");

  public CareCapability {
    Objects.requireNonNull(id, "id");
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("CareCapability code must be a stable uppercase token");
    }
    requireDisplayName(displayName);
    parentCapabilityId = Objects.requireNonNull(parentCapabilityId, "parentCapabilityId");
    if (parentCapabilityId.filter(id::equals).isPresent()) {
      throw new IllegalArgumentException("CareCapability cannot be its own parent");
    }
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(status, "status");
    if (version < 1) {
      throw new IllegalArgumentException("CareCapability version must be positive");
    }
  }

  public CareCapability correctDisplayName(
      String correctedDisplayName, DirectoryProvenance correction) {
    if (status != DirectoryLifecycleStatus.ACTIVE) {
      throw new IllegalStateException("inactive CareCapability cannot be corrected");
    }
    return new CareCapability(
        id,
        code,
        correctedDisplayName,
        parentCapabilityId,
        source,
        correction,
        status,
        Math.addExact(version, 1));
  }

  public CareCapability deactivate(DirectoryProvenance reason) {
    if (status != DirectoryLifecycleStatus.ACTIVE) {
      throw new IllegalStateException("inactive CareCapability cannot be deactivated again");
    }
    return new CareCapability(
        id,
        code,
        displayName,
        parentCapabilityId,
        source,
        reason,
        DirectoryLifecycleStatus.INACTIVE,
        Math.addExact(version, 1));
  }

  private static void requireDisplayName(String value) {
    if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 200) {
      throw new IllegalArgumentException(
          "CareCapability display name must be trimmed and 1 to 200 characters");
    }
  }
}
