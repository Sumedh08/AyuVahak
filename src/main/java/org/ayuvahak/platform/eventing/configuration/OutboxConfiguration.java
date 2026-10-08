package org.ayuvahak.platform.eventing.configuration;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.ayuvahak.platform.event.EventEnvelopeJsonCodec;
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
  ThreadPoolTaskScheduler outboxPollingTaskScheduler() {
    // Keep @Scheduled polling separate from the executor used for in-flight lease heartbeats.
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("ayuvahak-outbox-polling-");
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
}
