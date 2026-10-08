package org.ayuvahak.platform.authorization.application;

import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;

/** A target-scope owner validates references before Identity & Access persists a grant. */
public interface GrantTargetScopeValidator {

  boolean supports(FunctionalAuthority authority);

  /**
   * Return false when the referenced target is missing, inactive, or has an invalid relationship.
   */
  boolean isValid(FunctionalAuthority authority, RequestedExecutionScope targetScope);
}
