package org.ayuvahak.platform.eventing.application;

import org.ayuvahak.platform.event.EventEnvelope;
import org.ayuvahak.platform.event.EventType;

/** The narrowly approved same-Volume event whose consumer is introduced in a later phase. */
public final class DeclaredFutureConsumerEvents {

  public static final EventType REFERRAL_CANCELLED = EventType.parse("referral.cancelled.v1");

  private DeclaredFutureConsumerEvents() {}

  public static boolean permitsWaiting(EventEnvelope event) {
    return REFERRAL_CANCELLED.equals(event.eventType()) && event.aggregatePosition().isPresent();
  }
}
