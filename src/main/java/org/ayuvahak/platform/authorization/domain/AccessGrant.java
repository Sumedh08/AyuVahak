package org.ayuvahak.platform.authorization.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;

/** A platform-scoped authorization grant owned by Identity & Access. */
public record AccessGrant(
    InternalId id,
    ActorReference principal,
    String authorityCode,
    GrantStatus status,
    Instant createdAt,
    Optional<Instant> revokedAt) {

  public AccessGrant {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(principal, "principal");
    if (authorityCode == null || authorityCode.isBlank()) {
      throw new IllegalArgumentException("authority code must be nonblank");
    }
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(revokedAt, "revokedAt");
    if ((status == GrantStatus.REVOKED) != revokedAt.isPresent()) {
      throw new IllegalArgumentException("revoked timestamp must match the grant status");
    }
  }

  @Override
  public String toString() {
    return "AccessGrant[id="
        + id
        + ", principal=redacted, authorityCode="
        + authorityCode
        + ", status="
        + status
        + "]";
  }
}
