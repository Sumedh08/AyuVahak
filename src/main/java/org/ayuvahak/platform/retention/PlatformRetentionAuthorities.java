package org.ayuvahak.platform.retention;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Platform-owned authority for exporting/reapplying owner retention evidence. */
public final class PlatformRetentionAuthorities {

  private static final FunctionalAuthority REAPPLICATION =
      new FunctionalAuthority(
          "PlatformRetentionReapplication", Set.of(ExecutionScope.Kind.PLATFORM));

  private PlatformRetentionAuthorities() {}

  public static FunctionalAuthority reapplication() {
    return REAPPLICATION;
  }
}
