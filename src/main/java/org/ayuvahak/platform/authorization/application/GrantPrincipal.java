package org.ayuvahak.platform.authorization.application;

import java.util.Objects;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.IssuerId;

/** Validated issuer-scoped grantee reference accepted by platform grant administration. */
public record GrantPrincipal(ActorKind kind, IssuerId issuer, String identifier) {

  public GrantPrincipal {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(issuer, "issuer");
    if (identifier == null
        || identifier.isBlank()
        || !identifier.equals(identifier.strip())
        || identifier.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("principal identifier must be nonblank and safe");
    }
  }

  public ActorReference actorReference() {
    return switch (kind) {
      case HUMAN -> ActorReference.human(issuer, identifier);
      case SERVICE -> ActorReference.service(issuer, identifier);
    };
  }
}
