package org.ayuvahak.platform.authorization.application;

import java.util.Objects;
import org.ayuvahak.platform.authorization.domain.GrantScope;

/**
 * Minimal capability projection; grant identity and lifecycle metadata are intentionally absent.
 */
public record GrantedSubjectCapability(String authorityCode, GrantScope scope) {

  public GrantedSubjectCapability {
    if (authorityCode == null || authorityCode.isBlank()) {
      throw new IllegalArgumentException("authority code must be nonblank");
    }
    Objects.requireNonNull(scope, "scope");
  }
}
