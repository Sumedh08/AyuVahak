package org.ayuvahak.matching.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.ayuvahak.matching.domain.RecommendationRanker.Policy;
import org.ayuvahak.platform.core.ExternalReference;

/** One immutable, deployment-selected policy basis for a Recommendation evaluation. */
public record MatchingRecommendationPolicy(
    RecommendationSnapshot.PolicyVersion matchingPolicy,
    RecommendationSnapshot.PolicyVersion mappingPolicy,
    CapabilityOperationalMapping capabilityMapping,
    Set<ExternalReference> candidateOrganizations,
    Policy rankingPolicy,
    Duration maximumRecommendationAge,
    int directoryPageSize,
    boolean destinationLocationRequired) {

  public MatchingRecommendationPolicy {
    Objects.requireNonNull(matchingPolicy, "Matching policy version");
    Objects.requireNonNull(mappingPolicy, "mapping policy version");
    Objects.requireNonNull(capabilityMapping, "capability mapping");
    if (!mappingPolicy.reference().value().equals(capabilityMapping.mappingCode())
        || mappingPolicy.version() != capabilityMapping.version()) {
      throw new IllegalArgumentException("mapping policy identity must match its mapping");
    }
    candidateOrganizations = Set.copyOf(candidateOrganizations);
    if (candidateOrganizations.isEmpty()
        || candidateOrganizations.stream().anyMatch(Objects::isNull)
        || candidateOrganizations.stream()
            .anyMatch(organization -> !"Organization".equals(organization.resourceType()))) {
      throw new IllegalArgumentException("an explicit Organization search scope is required");
    }
    Objects.requireNonNull(rankingPolicy, "ranking policy");
    Objects.requireNonNull(maximumRecommendationAge, "maximum recommendation age");
    if (maximumRecommendationAge.isZero() || maximumRecommendationAge.isNegative()) {
      throw new IllegalArgumentException("maximum recommendation age must be positive");
    }
    if (directoryPageSize < 1 || directoryPageSize > 100) {
      throw new IllegalArgumentException("Directory page size must be between 1 and 100");
    }
  }
}
