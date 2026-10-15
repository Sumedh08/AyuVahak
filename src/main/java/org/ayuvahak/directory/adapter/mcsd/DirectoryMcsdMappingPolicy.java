package org.ayuvahak.directory.adapter.mcsd;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit source-profile rules required before mCSD facts can enter Directory. */
public record DirectoryMcsdMappingPolicy(
    Map<String, Set<String>> allowedProfilesByResourceType,
    Set<CodingKey> facilityLocationTypes,
    Set<OrderedLastUpdatedProfile> orderedLastUpdatedProfiles) {

  private static final Set<String> SUPPORTED_RESOURCE_TYPES =
      Set.of("Organization", "Location", "HealthcareService", "Endpoint");

  public DirectoryMcsdMappingPolicy {
    Objects.requireNonNull(allowedProfilesByResourceType, "allowedProfilesByResourceType");
    Objects.requireNonNull(facilityLocationTypes, "facilityLocationTypes");
    Objects.requireNonNull(orderedLastUpdatedProfiles, "orderedLastUpdatedProfiles");
    allowedProfilesByResourceType =
        allowedProfilesByResourceType.entrySet().stream()
            .peek(
                entry -> {
                  if (!SUPPORTED_RESOURCE_TYPES.contains(entry.getKey())
                      || entry.getValue() == null
                      || entry.getValue().isEmpty()
                      || entry.getValue().stream().anyMatch(String::isBlank)) {
                    throw new IllegalArgumentException(
                        "each supported Directory resource requires an explicit profile allowlist");
                  }
                })
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    facilityLocationTypes = Set.copyOf(facilityLocationTypes);
    orderedLastUpdatedProfiles = Set.copyOf(orderedLastUpdatedProfiles);
  }

  public boolean allowsProfile(String resourceType, String profileUri) {
    return allowedProfilesByResourceType.getOrDefault(resourceType, Set.of()).contains(profileUri);
  }

  public boolean classifiesFacility(LocationCoding coding) {
    return facilityLocationTypes.contains(new CodingKey(coding.system(), coding.code()));
  }

  public boolean treatsLastUpdatedAsOrderedRevision(String resourceType, String profileUri) {
    return orderedLastUpdatedProfiles.contains(
        new OrderedLastUpdatedProfile(resourceType, profileUri));
  }

  /** A source-profile assertion; FHIR alone does not promise lastUpdated is a revision sequence. */
  public record OrderedLastUpdatedProfile(String resourceType, String profileUri) {

    public OrderedLastUpdatedProfile {
      if (!SUPPORTED_RESOURCE_TYPES.contains(resourceType)
          || profileUri == null
          || profileUri.isBlank()) {
        throw new IllegalArgumentException(
            "an ordered source revision requires a resource profile");
      }
    }
  }

  public record CodingKey(String system, String code) {

    public CodingKey {
      if (system == null || system.isBlank() || code == null || code.isBlank()) {
        throw new IllegalArgumentException("facility location coding requires a system and code");
      }
    }
  }

  public record LocationCoding(String system, String code) {}
}
