package org.ayuvahak.platform.authorization.domain;

import java.util.Objects;
import java.util.Set;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** A locally defined permission function and the execution-scope kinds it may govern. */
public record FunctionalAuthority(String code, Set<ExecutionScope.Kind> allowedScopeKinds) {

  public FunctionalAuthority {
    if (code == null || !code.matches("[A-Za-z][A-Za-z0-9]{0,63}")) {
      throw new IllegalArgumentException("authority code must be a short identifier");
    }
    Objects.requireNonNull(allowedScopeKinds, "allowed scope kinds");
    allowedScopeKinds = Set.copyOf(allowedScopeKinds);
    if (allowedScopeKinds.isEmpty()) {
      throw new IllegalArgumentException("an authority must permit at least one scope kind");
    }
  }

  @Override
  public String toString() {
    return "FunctionalAuthority[code=" + code + "]";
  }
}
