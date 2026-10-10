package org.ayuvahak.matching.adapter.configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ayuvahak.matching.application.MatchingPolicyCatalog;
import org.ayuvahak.matching.domain.CapabilityOperationalMapping;
import org.ayuvahak.matching.domain.MatchingRecommendationPolicy;
import org.ayuvahak.matching.domain.RecommendationRanker.MetricKind;
import org.ayuvahak.matching.domain.RecommendationRanker.Policy;
import org.ayuvahak.matching.domain.RecommendationSnapshot.PolicyVersion;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional deployment policy. No production mapping or candidate scope is supplied by default. */
@ConfigurationProperties("ayuvahak.matching.policy")
public class MatchingPolicyProperties {

  private static final IssuerId MATCHING_ISSUER = new IssuerId("urn:ayuvahak:matching");
  private static final IssuerId DIRECTORY_ISSUER = new IssuerId("urn:ayuvahak:directory");

  private boolean active;
  private String code;
  private Long version;
  private String mappingCode;
  private Long mappingVersion;
  private Map<String, Set<String>> dimensionsByCapability = new LinkedHashMap<>();
  private List<String> candidateOrganizationIds = List.of();
  private String primaryMetric;
  private Boolean allowDistanceFallback;
  private Map<String, Integer> preferenceOrdinals = new LinkedHashMap<>();
  private Duration maximumRecommendationAge;
  private int directoryPageSize = 100;
  private boolean destinationLocationRequired = true;

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public String getMappingCode() {
    return mappingCode;
  }

  public void setMappingCode(String mappingCode) {
    this.mappingCode = mappingCode;
  }

  public Long getMappingVersion() {
    return mappingVersion;
  }

  public void setMappingVersion(Long mappingVersion) {
    this.mappingVersion = mappingVersion;
  }

  public Map<String, Set<String>> getDimensionsByCapability() {
    return dimensionsByCapability;
  }

  public void setDimensionsByCapability(Map<String, Set<String>> dimensionsByCapability) {
    this.dimensionsByCapability =
        dimensionsByCapability == null ? new LinkedHashMap<>() : dimensionsByCapability;
  }

  public List<String> getCandidateOrganizationIds() {
    return candidateOrganizationIds;
  }

  public void setCandidateOrganizationIds(List<String> candidateOrganizationIds) {
    this.candidateOrganizationIds =
        candidateOrganizationIds == null ? List.of() : List.copyOf(candidateOrganizationIds);
  }

  public String getPrimaryMetric() {
    return primaryMetric;
  }

  public void setPrimaryMetric(String primaryMetric) {
    this.primaryMetric = primaryMetric;
  }

  public Boolean getAllowDistanceFallback() {
    return allowDistanceFallback;
  }

  public void setAllowDistanceFallback(Boolean allowDistanceFallback) {
    this.allowDistanceFallback = allowDistanceFallback;
  }

  public Map<String, Integer> getPreferenceOrdinals() {
    return preferenceOrdinals;
  }

  public void setPreferenceOrdinals(Map<String, Integer> preferenceOrdinals) {
    this.preferenceOrdinals =
        preferenceOrdinals == null ? new LinkedHashMap<>() : preferenceOrdinals;
  }

  public Duration getMaximumRecommendationAge() {
    return maximumRecommendationAge;
  }

  public void setMaximumRecommendationAge(Duration maximumRecommendationAge) {
    this.maximumRecommendationAge = maximumRecommendationAge;
  }

  public int getDirectoryPageSize() {
    return directoryPageSize;
  }

  public void setDirectoryPageSize(int directoryPageSize) {
    this.directoryPageSize = directoryPageSize;
  }

  public boolean isDestinationLocationRequired() {
    return destinationLocationRequired;
  }

  public void setDestinationLocationRequired(boolean destinationLocationRequired) {
    this.destinationLocationRequired = destinationLocationRequired;
  }

  MatchingPolicyCatalog catalog() {
    var selected =
        active
            ? java.util.Optional.of(buildActivePolicy())
            : java.util.Optional.<MatchingRecommendationPolicy>empty();
    return () -> selected;
  }

  private MatchingRecommendationPolicy buildActivePolicy() {
    String policyCode = requiredToken(code, "Matching policy code");
    long policyVersion = requiredVersion(version, "Matching policy version");
    String resolvedMappingCode = requiredToken(mappingCode, "mapping policy code");
    long resolvedMappingVersion = requiredVersion(mappingVersion, "mapping policy version");
    if (candidateOrganizationIds.isEmpty()) {
      throw new IllegalArgumentException("active Matching policy requires candidate Organizations");
    }
    if (primaryMetric == null || allowDistanceFallback == null) {
      throw new IllegalArgumentException("active Matching policy requires ranking settings");
    }
    if (maximumRecommendationAge == null
        || maximumRecommendationAge.isNegative()
        || maximumRecommendationAge.isZero()) {
      throw new IllegalArgumentException("active Matching policy requires a positive maximum age");
    }

    Set<ExternalReference> organizations = new LinkedHashSet<>();
    candidateOrganizationIds.forEach(
        id -> organizations.add(directoryReference("Organization", id)));
    Map<ExternalReference, Integer> preferences = new LinkedHashMap<>();
    preferenceOrdinals.forEach(
        (id, ordinal) -> preferences.put(directoryReference("Facility", id), ordinal));
    CapabilityOperationalMapping mapping =
        new CapabilityOperationalMapping(
            resolvedMappingCode, resolvedMappingVersion, dimensionsByCapability);
    return new MatchingRecommendationPolicy(
        new PolicyVersion(
            new ExternalReference(MATCHING_ISSUER, "MatchingPolicy", policyCode), policyVersion),
        new PolicyVersion(
            new ExternalReference(
                MATCHING_ISSUER, "CapabilityOperationalRequirementPolicy", resolvedMappingCode),
            resolvedMappingVersion),
        mapping,
        organizations,
        new Policy(MetricKind.valueOf(primaryMetric), allowDistanceFallback, preferences),
        maximumRecommendationAge,
        directoryPageSize,
        destinationLocationRequired);
  }

  private static ExternalReference directoryReference(String type, String value) {
    InternalId.parse(value);
    return new ExternalReference(DIRECTORY_ISSUER, type, value);
  }

  private static String requiredToken(String value, String label) {
    if (value == null || !value.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException(label + " must be a stable uppercase identifier");
    }
    return value;
  }

  private static long requiredVersion(Long value, String label) {
    if (value == null || value < 1) {
      throw new IllegalArgumentException(label + " must be positive");
    }
    return value;
  }
}
