package org.ayuvahak.capacity.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Capacity-owned functional authorities and their permitted execution scopes. */
public final class CapacityAuthorities {

  private static final FunctionalAuthority POLICY_ADMINISTRATION =
      new FunctionalAuthority("CapacityPolicyAdministration", Set.of(ExecutionScope.Kind.PLATFORM));
  private static final FunctionalAuthority REPORTING =
      new FunctionalAuthority("CapacityReporting", Set.of(ExecutionScope.Kind.FACILITY));
  private static final FunctionalAuthority EVIDENCE_REVIEW =
      new FunctionalAuthority("CapacityEvidenceReview", Set.of(ExecutionScope.Kind.FACILITY));

  private CapacityAuthorities() {}

  public static FunctionalAuthority policyAdministration() {
    return POLICY_ADMINISTRATION;
  }

  public static FunctionalAuthority reporting() {
    return REPORTING;
  }

  public static FunctionalAuthority evidenceReview() {
    return EVIDENCE_REVIEW;
  }
}
