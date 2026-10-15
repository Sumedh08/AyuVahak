package org.ayuvahak.transport.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Narrow Transport permissions used by owner-validated coordination commands. */
public final class TransportAuthorities {

  private static final Set<ExecutionScope.Kind> FACILITY_SCOPE =
      Set.of(ExecutionScope.Kind.FACILITY);
  private static final FunctionalAuthority REQUEST =
      new FunctionalAuthority("TransportRequest", FACILITY_SCOPE);

  private TransportAuthorities() {}

  public static FunctionalAuthority request() {
    return REQUEST;
  }
}
