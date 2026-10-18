package org.ayuvahak.analytics.adapter;

import org.ayuvahak.analytics.application.AnalyticsAggregateComputationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the durable Analytics request queue; failure delays reporting but not owner workflows. */
@Component
public final class AnalyticsComputationPollingWorker {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(AnalyticsComputationPollingWorker.class);
  private static final int MAX_BATCH = 16;

  private final AnalyticsAggregateComputationService computations;

  public AnalyticsComputationPollingWorker(AnalyticsAggregateComputationService computations) {
    this.computations = computations;
  }

  @Scheduled(fixedDelayString = "${ayuvahak.analytics.computation.poll-interval:PT2S}")
  public void poll() {
    try {
      for (int count = 0; count < MAX_BATCH; count++) {
        if (computations.processNext().isEmpty()) {
          return;
        }
      }
    } catch (RuntimeException unavailable) {
      LOGGER.error("Analytics computation polling failed; durable requests remain recoverable.");
    }
  }
}
