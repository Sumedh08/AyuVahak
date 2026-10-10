package org.ayuvahak.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.junit.jupiter.api.Test;

class MatchingAuthoritiesTest {

  @Test
  void humanRecommendationAuthoritiesAreLimitedToOriginScopes() {
    assertThat(MatchingAuthorities.requestRecommendation().code())
        .isEqualTo("RecommendationRequest");
    assertThat(MatchingAuthorities.requestRecommendation().allowedScopeKinds())
        .containsExactlyInAnyOrder(ExecutionScope.Kind.ORGANIZATION, ExecutionScope.Kind.FACILITY);
    assertThat(MatchingAuthorities.readRecommendation().allowedScopeKinds())
        .containsExactlyInAnyOrder(ExecutionScope.Kind.ORGANIZATION, ExecutionScope.Kind.FACILITY);
  }

  @Test
  void matchingDoesNotOwnTheOwnerReadAuthorities() {
    Set<String> registeredMatchingAuthorities =
        Set.of(
            MatchingAuthorities.requestRecommendation().code(),
            MatchingAuthorities.readRecommendation().code());

    assertThat(registeredMatchingAuthorities)
        .doesNotContain("ReferralRead", "DirectoryRead", "CapacityEvidenceReview");
  }
}
