package org.ayuvahak.platform.operatorsupport.application;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Least-privilege platform grant for reading bounded operator guidance. */
public final class OperatorSupportAuthorities {

  public static final String READ_CODE = "OperatorSupportRead";

  private static final FunctionalAuthority READ =
      new FunctionalAuthority(READ_CODE, Set.of(ExecutionScope.Kind.PLATFORM));

  private OperatorSupportAuthorities() {}

  public static FunctionalAuthority read() {
    return READ;
  }
}
