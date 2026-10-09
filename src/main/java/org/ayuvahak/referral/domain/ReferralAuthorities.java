package org.ayuvahak.referral.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Referral-owned functional authorities for origin-scoped draft commands. */
public final class ReferralAuthorities {

  private static final Set<ExecutionScope.Kind> ORIGIN_SCOPES =
      Set.of(ExecutionScope.Kind.ORGANIZATION, ExecutionScope.Kind.FACILITY);
  private static final FunctionalAuthority CREATE =
      new FunctionalAuthority("ReferralCreate", ORIGIN_SCOPES);
  private static final FunctionalAuthority EDIT =
      new FunctionalAuthority("ReferralEdit", ORIGIN_SCOPES);
  private static final FunctionalAuthority SUBMIT =
      new FunctionalAuthority("ReferralSubmit", ORIGIN_SCOPES);
  private static final FunctionalAuthority AMEND =
      new FunctionalAuthority("ReferralAmend", ORIGIN_SCOPES);
  private static final FunctionalAuthority RESOLVE =
      new FunctionalAuthority("ReferralResolve", ORIGIN_SCOPES);

  private ReferralAuthorities() {}

  public static FunctionalAuthority create() {
    return CREATE;
  }

  public static FunctionalAuthority edit() {
    return EDIT;
  }

  public static FunctionalAuthority submit() {
    return SUBMIT;
  }

  public static FunctionalAuthority amend() {
    return AMEND;
  }

  public static FunctionalAuthority resolve() {
    return RESOLVE;
  }
}
