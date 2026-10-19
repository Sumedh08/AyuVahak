package org.ayuvahak.platform.eventing.configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxConfiguration {

  @Bean
  Clock outboxClock() {
    return Clock.systemUTC();
  }

  @Bean(name = "taskScheduler")
  ThreadPoolTaskScheduler outboxPollingTaskScheduler(
      @Value("${ayuvahak.operations.shutdown.worker-timeout:20s}") Duration workerShutdownTimeout) {
    // Keep @Scheduled polling separate from the executor used for in-flight lease heartbeats.
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("ayuvahak-outbox-polling-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationMillis(requireShutdownTimeout(workerShutdownTimeout));
    scheduler.setAcceptTasksAfterContextClose(false);
    scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  @Bean
  EventEnvelopeJsonCodec eventEnvelopeJsonCodec(ObjectMapper objectMapper) {
    return new EventEnvelopeJsonCodec(objectMapper);
  }

  @Bean(destroyMethod = "shutdownNow")
  ScheduledExecutorService outboxLeaseRenewalExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        task -> {
          Thread thread = new Thread(task, "ayuvahak-outbox-lease-renewal");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static long requireShutdownTimeout(Duration timeout) {
    if (timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException(
          "worker shutdown timeout must be greater than zero and no longer than five minutes");
    }
    return timeout.toMillis();
  }
}
