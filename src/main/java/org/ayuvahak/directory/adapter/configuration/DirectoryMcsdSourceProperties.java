package org.ayuvahak.directory.adapter.configuration;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned mapping from a stable Directory source namespace to a partner key. */
@ConfigurationProperties("ayuvahak.directory.mcsd")
public class DirectoryMcsdSourceProperties {

  private List<SourceBinding> sourceBindings = List.of();
  private List<FacilityLocationCoding> facilityLocationTypes = List.of();
  private List<OrderedLastUpdatedProfile> orderedLastUpdatedProfiles = List.of();

  public List<SourceBinding> getSourceBindings() {
    return sourceBindings;
  }

  public void setSourceBindings(List<SourceBinding> sourceBindings) {
    this.sourceBindings = List.copyOf(Objects.requireNonNull(sourceBindings, "sourceBindings"));
  }

  public List<FacilityLocationCoding> getFacilityLocationTypes() {
    return facilityLocationTypes;
  }

  public void setFacilityLocationTypes(List<FacilityLocationCoding> facilityLocationTypes) {
    this.facilityLocationTypes =
        List.copyOf(Objects.requireNonNull(facilityLocationTypes, "facilityLocationTypes"));
  }

  public List<OrderedLastUpdatedProfile> getOrderedLastUpdatedProfiles() {
    return orderedLastUpdatedProfiles;
  }

  public void setOrderedLastUpdatedProfiles(
      List<OrderedLastUpdatedProfile> orderedLastUpdatedProfiles) {
    this.orderedLastUpdatedProfiles =
        List.copyOf(
            Objects.requireNonNull(orderedLastUpdatedProfiles, "orderedLastUpdatedProfiles"));
  }

  public record FacilityLocationCoding(String system, String code) {

    public FacilityLocationCoding {
      if (system == null || system.isBlank() || code == null || code.isBlank()) {
        throw new IllegalArgumentException("facility location coding requires system and code");
      }
    }
  }

  public record OrderedLastUpdatedProfile(String resourceType, String profileUri) {

    public OrderedLastUpdatedProfile {
      if (resourceType == null
          || resourceType.isBlank()
          || profileUri == null
          || profileUri.isBlank()) {
        throw new IllegalArgumentException("ordered source revision profile is invalid");
      }
    }
  }

  public record SourceBinding(String sourceNamespace, String partnerKey) {

    public SourceBinding {
      if (sourceNamespace == null
          || sourceNamespace.isBlank()
          || !sourceNamespace.equals(sourceNamespace.strip())
          || sourceNamespace.codePoints().anyMatch(Character::isISOControl)
          || partnerKey == null
          || partnerKey.isBlank()
          || !partnerKey.equals(partnerKey.strip())) {
        throw new IllegalArgumentException("mCSD source binding is invalid");
      }
    }
  }
}
