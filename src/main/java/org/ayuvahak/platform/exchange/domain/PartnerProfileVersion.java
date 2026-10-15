package org.ayuvahak.platform.exchange.domain;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;

/** Immutable identity and reviewed configuration version plus its current trust lifecycle state. */
public record PartnerProfileVersion(
    InternalId id,
    int version,
    PartnerProfileState state,
    PartnerTrustConfiguration configuration,
    Instant createdAt,
    Instant updatedAt) {

  public PartnerProfileVersion {
    Objects.requireNonNull(id, "id");
    if (version < 1) {
      throw new IllegalArgumentException("partner configuration version must be positive");
    }
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
