package org.ayuvahak.platform.authorization.domain;

import java.util.Objects;
import java.util.Optional;

/** A minimized outcome; it carries no subject, grant, target, or policy detail. */
public record AuthorizationDecision(Outcome outcome, Optional<DenialReason> denialReason) {

  public enum Outcome {
    ALLOW,
    DENY
  }

  public enum DenialReason {
    EXECUTION_CONTEXT_REQUIRED,
    AUTHORITY_NOT_CONFIGURED,
    SCOPE_NOT_SUPPORTED,
    NO_ACTIVE_GRANT,
    GRANT_STORE_UNAVAILABLE
  }

  public AuthorizationDecision {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(denialReason, "denial reason");
    if ((outcome == Outcome.ALLOW) == denialReason.isPresent()) {
      throw new IllegalArgumentException(
          "allow decisions have no denial reason; denials require one");
    }
  }

  public static AuthorizationDecision allow() {
    return new AuthorizationDecision(Outcome.ALLOW, Optional.empty());
  }

  public static AuthorizationDecision deny(DenialReason reason) {
    return new AuthorizationDecision(Outcome.DENY, Optional.of(reason));
  }

  public boolean isAllowed() {
    return outcome == Outcome.ALLOW;
  }
}
