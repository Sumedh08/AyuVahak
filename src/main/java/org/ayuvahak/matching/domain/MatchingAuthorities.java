package org.ayuvahak.matching.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Matching-owned human authorities; owner-read permissions remain with their owning contexts. */
public final class MatchingAuthorities {

  private static final Set<ExecutionScope.Kind> ORIGIN_SCOPES =
      Set.of(ExecutionScope.Kind.ORGANIZATION, ExecutionScope.Kind.FACILITY);
  private static final FunctionalAuthority REQUEST_RECOMMENDATION =
      new FunctionalAuthority("RecommendationRequest", ORIGIN_SCOPES);
  private static final FunctionalAuthority READ_RECOMMENDATION =
      new FunctionalAuthority("RecommendationRead", ORIGIN_SCOPES);

  private MatchingAuthorities() {}

  public static FunctionalAuthority requestRecommendation() {
    return REQUEST_RECOMMENDATION;
  }

  public static FunctionalAuthority readRecommendation() {
    return READ_RECOMMENDATION;
  }
}
