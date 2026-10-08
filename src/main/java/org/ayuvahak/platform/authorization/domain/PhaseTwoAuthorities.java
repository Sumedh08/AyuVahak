package org.ayuvahak.platform.authorization.domain;

import java.util.List;
import java.util.Set;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** The only production functional authorities registered by the Phase 2 platform kernel. */
public final class PhaseTwoAuthorities {

  public static final FunctionalAuthority ACCESS_GRANT_ADMINISTRATION =
      platform("AccessGrantAdministration");
  public static final FunctionalAuthority AUDIT_REVIEW = platform("AuditReview");
  public static final FunctionalAuthority EVENT_DELIVERY_OPERATIONS =
      platform("EventDeliveryOperations");

  private PhaseTwoAuthorities() {}

  public static FunctionalAuthorityCatalog catalog() {
    return new FunctionalAuthorityCatalog(
        List.of(ACCESS_GRANT_ADMINISTRATION, AUDIT_REVIEW, EVENT_DELIVERY_OPERATIONS));
  }

  private static FunctionalAuthority platform(String code) {
    return new FunctionalAuthority(code, Set.of(ExecutionScope.Kind.PLATFORM));
  }
}
