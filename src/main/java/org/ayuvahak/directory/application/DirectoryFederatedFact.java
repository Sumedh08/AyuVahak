package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Allowlisted remote Directory fact; deliberately smaller than the local Directory model. */
public record DirectoryFederatedFact(
    DirectoryRemoteResourceReference reference,
    Optional<DirectoryRemoteResourceReference> organization,
    String displayName,
    boolean active,
    long sourceVersion,
    Optional<Instant> sourceUpdatedAt,
    String sourcePolicyVersion) {

  public DirectoryFederatedFact {
    Objects.requireNonNull(reference, "reference");
    organization = Objects.requireNonNull(organization, "organization");
    sourceUpdatedAt = Objects.requireNonNull(sourceUpdatedAt, "sourceUpdatedAt");
    if (displayName == null
        || displayName.isBlank()
        || !displayName.equals(displayName.strip())
        || displayName.length() > 256
        || displayName.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("remote Directory display name is outside its bound");
    }
    if (sourceVersion < 1) {
      throw new IllegalArgumentException("source version must be positive");
    }
    if (sourcePolicyVersion == null
        || !sourcePolicyVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("source policy version must be a bounded token");
    }
    organization.ifPresent(
        value -> {
          if (!value.deploymentId().equals(reference.deploymentId())
              || !"Organization".equals(value.resourceType())) {
            throw new IllegalArgumentException(
                "a remote Directory organization reference must use the same source deployment");
          }
        });
    if ("Facility".equals(reference.resourceType()) && organization.isEmpty()) {
      throw new IllegalArgumentException("a remote Facility requires its source Organization");
    }
    if ("Organization".equals(reference.resourceType()) && organization.isPresent()) {
      throw new IllegalArgumentException("a remote Organization cannot have a parent Organization");
    }
  }

  @Override
  public String toString() {
    return "DirectoryFederatedFact[reference="
        + reference
        + ", organization="
        + organization.map(ignored -> "present").orElse("absent")
        + ", displayName=redacted, active="
        + active
        + ", sourceVersion="
        + sourceVersion
        + ", sourcePolicyVersion="
        + sourcePolicyVersion
        + "]";
  }
}
