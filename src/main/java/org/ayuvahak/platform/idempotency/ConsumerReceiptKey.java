package org.ayuvahak.platform.idempotency;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;

/** Stable deduplication key stored with one consumer's own effect and receipt. */
public record ConsumerReceiptKey(String consumer, InternalId eventId) {

  public ConsumerReceiptKey {
    if (consumer == null || !consumer.matches("[a-z][a-z0-9.-]{0,63}")) {
      throw new IllegalArgumentException("consumer must be a stable lowercase identifier");
    }
    Objects.requireNonNull(eventId, "eventId");
  }

  @Override
  public String toString() {
    return "ConsumerReceiptKey[consumer=" + consumer + ", eventId=" + eventId + "]";
  }
}
