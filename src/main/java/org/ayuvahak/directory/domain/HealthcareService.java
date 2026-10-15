package org.ayuvahak.directory.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** An organized care offering at a Facility, separate from capability evidence and capacity. */
public record HealthcareService(
    InternalId id,
    InternalId organizationId,
    InternalId facilityId,
    Optional<InternalId> locationId,
    String name,
    List<DirectoryServiceType> serviceTypes,
    DirectorySource source,
    DirectoryProvenance provenance,
    DirectoryLifecycleStatus status,
    long version) {

  public HealthcareService {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(facilityId, "facilityId");
    locationId = Objects.requireNonNull(locationId, "locationId");
    requireName(name);
    serviceTypes = List.copyOf(Objects.requireNonNull(serviceTypes, "serviceTypes"));
    if (serviceTypes.size() > 16) {
      throw new IllegalArgumentException("HealthcareService service type count must not exceed 16");
    }
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(status, "status");
    if (version < 1) {
      throw new IllegalArgumentException("HealthcareService version must be positive");
    }
  }

  public HealthcareService(
      InternalId id,
      InternalId organizationId,
      InternalId facilityId,
      Optional<InternalId> locationId,
      String name,
      DirectorySource source,
      DirectoryProvenance provenance,
      DirectoryLifecycleStatus status,
      long version) {
    this(
        id,
        organizationId,
        facilityId,
        locationId,
        name,
        List.of(),
        source,
        provenance,
        status,
        version);
  }

  public HealthcareService correct(
      String correctedName,
      Optional<InternalId> correctedLocation,
      DirectoryProvenance correction) {
    return correct(correctedName, correctedLocation, serviceTypes, correction);
  }

  public HealthcareService correct(
      String correctedName,
      Optional<InternalId> correctedLocation,
      List<DirectoryServiceType> correctedServiceTypes,
      DirectoryProvenance correction) {
    if (status != DirectoryLifecycleStatus.ACTIVE) {
      throw new IllegalStateException("inactive HealthcareService cannot be corrected");
    }
    return new HealthcareService(
        id,
        organizationId,
        facilityId,
        correctedLocation,
        correctedName,
        correctedServiceTypes,
        source,
        correction,
        status,
        Math.addExact(version, 1));
  }

  public HealthcareService deactivate(DirectoryProvenance reason) {
    if (status != DirectoryLifecycleStatus.ACTIVE) {
      throw new IllegalStateException("inactive HealthcareService cannot be deactivated again");
    }
    return new HealthcareService(
        id,
        organizationId,
        facilityId,
        locationId,
        name,
        serviceTypes,
        source,
        reason,
        DirectoryLifecycleStatus.INACTIVE,
        Math.addExact(version, 1));
  }

  private static void requireName(String value) {
    if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 200) {
      throw new IllegalArgumentException(
          "HealthcareService name must be trimmed and 1 to 200 characters");
    }
  }
}
