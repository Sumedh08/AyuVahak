package org.ayuvahak.platform.authorization.application;

import java.util.Objects;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.springframework.dao.DataAccessException;

/** Evaluates local grants for a verified actor within an already validated execution context. */
public final class AuthorizationService {

  private final AuthorizationGrantLookup grantLookup;
  private final FunctionalAuthorityCatalog authorities;

  public AuthorizationService(
      AuthorizationGrantLookup grantLookup, FunctionalAuthorityCatalog authorities) {
    this.grantLookup = Objects.requireNonNull(grantLookup, "grant lookup");
    this.authorities = Objects.requireNonNull(authorities, "authority catalog");
  }

  public AuthorizationDecision evaluate(ExecutionContext context, String authorityCode) {
    if (context == null || context.scope() == null) {
      return AuthorizationDecision.deny(
          AuthorizationDecision.DenialReason.EXECUTION_CONTEXT_REQUIRED);
    }
    FunctionalAuthority authority = authorities.find(authorityCode).orElse(null);
    if (authority == null) {
      return AuthorizationDecision.deny(
          AuthorizationDecision.DenialReason.AUTHORITY_NOT_CONFIGURED);
    }
    if (!authority.allowedScopeKinds().contains(context.scope().kind())) {
      return AuthorizationDecision.deny(AuthorizationDecision.DenialReason.SCOPE_NOT_SUPPORTED);
    }
    try {
      if (grantLookup.hasActiveGrant(context.actor(), authority, context.scope())) {
        return AuthorizationDecision.allow();
      }
      return AuthorizationDecision.deny(AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
    } catch (DataAccessException unavailable) {
      return AuthorizationDecision.deny(AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE);
    }
  }
}
