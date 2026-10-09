package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.referral.domain.ReferralState;

/** Allowlisted filters for an origin-scoped Referral list. */
public record ReferralReadFilter(
    Optional<ReferralState> state,
    Optional<Instant> fromInclusive,
    Optional<Instant> untilExclusive,
    int page,
    int size) {

  public ReferralReadFilter {
    state = Objects.requireNonNull(state, "state filter");
    fromInclusive = Objects.requireNonNull(fromInclusive, "from filter");
    untilExclusive = Objects.requireNonNull(untilExclusive, "until filter");
    if (page < 0 || size < 1 || size > 100 || page > Integer.MAX_VALUE / size) {
      throw new IllegalArgumentException("Referral page bounds are invalid");
    }
    if (fromInclusive.isPresent()
        && untilExclusive.isPresent()
        && !untilExclusive.orElseThrow().isAfter(fromInclusive.orElseThrow())) {
      throw new IllegalArgumentException("Referral time range is invalid");
    }
  }
}
