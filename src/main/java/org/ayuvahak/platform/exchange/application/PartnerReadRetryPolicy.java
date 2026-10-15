package org.ayuvahak.platform.exchange.application;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Bounded backoff rules for safe, idempotent partner reads. */
final class PartnerReadRetryPolicy {

  private static final Duration BASE_DELAY = Duration.ofMillis(200);
  private static final Duration MAX_DELAY = Duration.ofSeconds(30);

  private PartnerReadRetryPolicy() {}

  static boolean isRetryable(PartnerTransportFailure failure) {
    return switch (failure) {
      case DNS_FAILURE,
          DNS_TIMEOUT,
          CONNECT_TIMEOUT,
          READ_TIMEOUT,
          OPERATION_TIMEOUT,
          RATE_LIMITED,
          REMOTE_5XX,
          CONNECTION_FAILURE ->
          true;
      default -> false;
    };
  }

  static Duration delay(int failedAttempt, Optional<Duration> retryAfter) {
    int shift = Math.min(Math.max(failedAttempt - 1, 0), 7);
    long exponentialMillis = Math.min(BASE_DELAY.toMillis() << shift, MAX_DELAY.toMillis());
    long jitteredMillis =
        ThreadLocalRandom.current()
            .nextLong(Math.max(1, exponentialMillis / 2), exponentialMillis + 1);
    long serverMillis =
        retryAfter
            .map(Duration::toMillis)
            .map(value -> Math.max(0, Math.min(value, MAX_DELAY.toMillis())))
            .orElse(0L);
    return Duration.ofMillis(Math.max(jitteredMillis, serverMillis));
  }

  static void pause(Duration delay) {
    if (delay.isZero()) {
      return;
    }
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new PartnerTransportException(PartnerTransportFailure.OPERATION_TIMEOUT);
    }
  }
}
