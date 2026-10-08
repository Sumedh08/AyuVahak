package org.ayuvahak.platform.eventing.application;

/** Counts operational outcomes without retaining exception messages or event payloads. */
public record DispatchReport(
    int delivered, int retryScheduled, int stalled, int unhandled, int leaseLost) {

  public DispatchReport plus(DispatchReport other) {
    return new DispatchReport(
        delivered + other.delivered,
        retryScheduled + other.retryScheduled,
        stalled + other.stalled,
        unhandled + other.unhandled,
        leaseLost + other.leaseLost);
  }
}
