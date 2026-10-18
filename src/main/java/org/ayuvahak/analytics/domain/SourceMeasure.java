package org.ayuvahak.analytics.domain;

import java.util.Objects;

/** A bounded owner-produced scalar measure; no business row or case identity is addressable. */
public record SourceMeasure(Owner owner, String key, Attribution attribution) {

  public SourceMeasure {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(key, "measure key");
    Objects.requireNonNull(attribution, "attribution");
    if (!approved(owner, key, attribution)) {
      throw new IllegalArgumentException("unsupported reporting measure or attribution");
    }
  }

  public static SourceMeasure referral(String key) {
    return new SourceMeasure(Owner.REFERRAL, key, Attribution.NONE);
  }

  public static SourceMeasure transfer(String key, Attribution attribution) {
    return new SourceMeasure(Owner.TRANSFER, key, attribution);
  }

  public String stableKey() {
    return owner.name() + ":" + key + ":" + attribution.name();
  }

  private static boolean approved(Owner owner, String key, Attribution attribution) {
    return switch (owner) {
      case REFERRAL ->
          attribution == Attribution.NONE
              && (key.equals("SUBMITTED_COUNT") || key.equals("RESOLVED_COUNT"));
      case TRANSFER ->
          attribution != Attribution.NONE
              && (key.equals("TRANSFER_ACCEPTED")
                  || key.equals("TRANSFER_COMPLETED")
                  || key.equals("TRANSFER_UNFULFILLED"))
              && !(key.equals("TRANSFER_UNFULFILLED") && attribution == Attribution.DESTINATION);
    };
  }

  public enum Owner {
    REFERRAL,
    TRANSFER
  }

  public enum Attribution {
    NONE,
    ORIGIN,
    DESTINATION
  }
}
