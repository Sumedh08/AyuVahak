package org.ayuvahak.platform.notification.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Platform notification access is explicitly scoped to one organization or facility. */
public final class NotificationAuthorities {

  private static final FunctionalAuthority FEED_READ =
      new FunctionalAuthority(
          "NotificationFeedRead",
          Set.of(ExecutionScope.Kind.ORGANIZATION, ExecutionScope.Kind.FACILITY));

  private NotificationAuthorities() {}

  public static FunctionalAuthority feedRead() {
    return FEED_READ;
  }
}
