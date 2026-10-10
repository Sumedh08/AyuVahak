package org.ayuvahak.transfer.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Transfer-owned authorities for origin-scoped request initiation. */
public final class TransferAuthorities {

  private static final Set<ExecutionScope.Kind> ORIGIN_SCOPES =
      Set.of(ExecutionScope.Kind.ORGANIZATION, ExecutionScope.Kind.FACILITY);
  private static final FunctionalAuthority REQUEST =
      new FunctionalAuthority("TransferRequest", ORIGIN_SCOPES);

  private TransferAuthorities() {}

  public static FunctionalAuthority request() {
    return REQUEST;
  }
}
