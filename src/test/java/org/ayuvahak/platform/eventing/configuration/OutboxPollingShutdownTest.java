package org.ayuvahak.platform.eventing.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class OutboxPollingShutdownTest {

  @Test
  void shutdownWaitsForTheCurrentDurableWorkUnitWithoutInterruptingIt() throws Exception {
    ThreadPoolTaskScheduler scheduler =
        new OutboxConfiguration().outboxPollingTaskScheduler(Duration.ofSeconds(20));
    scheduler.initialize();
    CountDownLatch workStarted = new CountDownLatch(1);
    CountDownLatch finishWork = new CountDownLatch(1);
    CountDownLatch shutdownFinished = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    scheduler.execute(
        () -> {
          workStarted.countDown();
          try {
            finishWork.await();
          } catch (InterruptedException shutdownInterruptedWork) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
          }
        });

    assertThat(workStarted.await(2, TimeUnit.SECONDS)).isTrue();
    Thread shutdown = new Thread(() -> shutdownScheduler(scheduler, shutdownFinished));
    shutdown.start();

    try {
      assertThat(shutdownFinished.await(100, TimeUnit.MILLISECONDS)).isFalse();
    } finally {
      finishWork.countDown();
    }

    assertThat(shutdownFinished.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(interrupted).isFalse();
    assertThat(scheduler.getScheduledExecutor().isShutdown()).isTrue();
  }

  private static void shutdownScheduler(
      ThreadPoolTaskScheduler scheduler, CountDownLatch shutdownFinished) {
    try {
      scheduler.destroy();
    } finally {
      shutdownFinished.countDown();
    }
  }
}
