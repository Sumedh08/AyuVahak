package org.ayuvahak.platform.eventing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.ayuvahak.platform.event.EventEnvelope;

/** Persistence boundary owned by the platform event-delivery capability. */
public interface OutboxStore {

  void record(EventEnvelope event);

  List<LeasedEvent> claimDue(Instant now, Duration leaseDuration, int maximumAttempts, int limit);

  boolean markUnhandled(LeaseToken token, Instant now);

  int beginAttempt(LeaseToken token, Instant now, int maximumAttempts);

  boolean renewLease(LeaseToken token, Instant now, Duration leaseDuration);

  boolean markDelivered(LeaseToken token, Instant now);

  int purgeDeliveredBefore(Instant cutoff, int limit);

  DeliveryFailureResult markFailed(
      LeaseToken token, Instant failedAt, Instant nextAttemptAt, int maximumAttempts);
}
