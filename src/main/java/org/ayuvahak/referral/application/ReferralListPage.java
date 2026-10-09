package org.ayuvahak.referral.application;

import java.util.List;
import java.util.Objects;

/** Bounded deterministic page of minimized Referral list views. */
public record ReferralListPage(List<ReferralListItem> items, int page, int size, boolean hasMore) {

  public ReferralListPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    if (page < 0 || size < 1 || items.size() > size) {
      throw new IllegalArgumentException("Referral list page bounds are invalid");
    }
  }
}
