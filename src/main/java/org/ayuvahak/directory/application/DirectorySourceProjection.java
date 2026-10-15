package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.domain.Wgs84Coordinate;
import org.ayuvahak.platform.core.ExternalReference;

/** Directory-owned, FHIR-neutral input contract for a source projection import. */
public sealed interface DirectorySourceProjection
    permits DirectorySourceProjection.OrganizationFact,
        DirectorySourceProjection.FacilityFact,
        DirectorySourceProjection.LocationFact,
        DirectorySourceProjection.HealthcareServiceFact,
        DirectorySourceProjection.EndpointMetadata {

  Metadata metadata();

  boolean active();

  record Metadata(
      ExternalReference sourceIdentity,
      Optional<String> opaqueVersionId,
      Optional<Instant> sourceUpdatedAt,
      boolean sourceUpdatedAtIsOrderedRevision,
      String profileUri,
      String projectionFingerprint) {

    public Metadata {
      Objects.requireNonNull(sourceIdentity, "sourceIdentity");
      opaqueVersionId = Objects.requireNonNull(opaqueVersionId, "opaqueVersionId");
      sourceUpdatedAt = Objects.requireNonNull(sourceUpdatedAt, "sourceUpdatedAt");
      if (profileUri == null || profileUri.isBlank()) {
        throw new IllegalArgumentException("source profile URI is required");
      }
      if (projectionFingerprint == null || !projectionFingerprint.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("projection fingerprint must be SHA-256 hex");
      }
    }
  }

  record OrganizationFact(
      Metadata metadata,
      String name,
      Optional<ExternalReference> parentOrganization,
      boolean active)
      implements DirectorySourceProjection {

    public OrganizationFact {
      Objects.requireNonNull(metadata, "metadata");
      requireName(name);
      parentOrganization = Objects.requireNonNull(parentOrganization, "parentOrganization");
    }
  }

  record FacilityFact(
      Metadata metadata,
      String name,
      ExternalReference managingOrganization,
      Optional<Wgs84Coordinate> coordinate,
      boolean active)
      implements DirectorySourceProjection {

    public FacilityFact {
      Objects.requireNonNull(metadata, "metadata");
      requireName(name);
      Objects.requireNonNull(managingOrganization, "managingOrganization");
      coordinate = Objects.requireNonNull(coordinate, "coordinate");
    }
  }

  record LocationFact(
      Metadata metadata,
      String name,
      ExternalReference facilityReference,
      Optional<Wgs84Coordinate> coordinate,
      boolean active)
      implements DirectorySourceProjection {

    public LocationFact {
      Objects.requireNonNull(metadata, "metadata");
      requireName(name);
      Objects.requireNonNull(facilityReference, "facilityReference");
      coordinate = Objects.requireNonNull(coordinate, "coordinate");
    }
  }

  record HealthcareServiceFact(
      Metadata metadata,
      String name,
      ExternalReference organizationReference,
      ExternalReference facilityReference,
      boolean active)
      implements DirectorySourceProjection {

    public HealthcareServiceFact {
      Objects.requireNonNull(metadata, "metadata");
      requireName(name);
      Objects.requireNonNull(organizationReference, "organizationReference");
      Objects.requireNonNull(facilityReference, "facilityReference");
    }
  }

  /** Endpoint data remains bounded discovery metadata and is never a network destination. */
  record EndpointMetadata(
      Metadata metadata,
      String address,
      Optional<ExternalReference> managingOrganization,
      Optional<Coding> connectionType,
      boolean active)
      implements DirectorySourceProjection {

    public EndpointMetadata {
      Objects.requireNonNull(metadata, "metadata");
      if (address == null || address.isBlank() || address.length() > 2048) {
        throw new IllegalArgumentException("endpoint metadata address must be bounded");
      }
      managingOrganization = Objects.requireNonNull(managingOrganization, "managingOrganization");
      connectionType = Objects.requireNonNull(connectionType, "connectionType");
    }

    public boolean trustedForNetworkUse() {
      return false;
    }
  }

  record Coding(String system, String code, Optional<String> version) {

    public Coding {
      if (system == null || system.isBlank() || code == null || code.isBlank()) {
        throw new IllegalArgumentException("source coding requires a system and code");
      }
      version = Objects.requireNonNull(version, "version");
    }
  }

  private static void requireName(String name) {
    if (name == null || name.isBlank() || !name.equals(name.strip()) || name.length() > 200) {
      throw new IllegalArgumentException("source name must be trimmed and 1 to 200 characters");
    }
  }
}
