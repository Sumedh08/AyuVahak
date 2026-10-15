package org.ayuvahak.platform.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class BoundedFhirValidationExecutorTest {

  @Test
  void cutsOffTimedOutValidationAndRequestsInterruption() throws Exception {
    try (BoundedFhirValidationExecutor executor = new BoundedFhirValidationExecutor(1)) {
      CountDownLatch taskStarted = new CountDownLatch(1);
      CountDownLatch taskInterrupted = new CountDownLatch(1);

      assertThatThrownBy(
              () ->
                  executor.execute(
                      () -> {
                        taskStarted.countDown();
                        try {
                          new CountDownLatch(1).await();
                        } catch (InterruptedException exception) {
                          taskInterrupted.countDown();
                          throw exception;
                        }
                        return "unreachable";
                      },
                      Duration.ofMillis(100)))
          .isInstanceOf(TimeoutException.class);

      assertThat(taskStarted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(taskInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(executor.activeTaskCount()).isLessThanOrEqualTo(1);
      assertThat(executor.queuedTaskCount()).isZero();
    }
  }

  @Test
  void boundsRunningAndQueuedValidationWorkAndRejectsExcess() throws Exception {
    try (BoundedFhirValidationExecutor executor = new BoundedFhirValidationExecutor(1)) {
      ExecutorService callers = Executors.newFixedThreadPool(2);
      CountDownLatch runningTaskStarted = new CountDownLatch(1);
      CountDownLatch releaseRunningTask = new CountDownLatch(1);
      Future<String> runningTask =
          callers.submit(
              () ->
                  executor.execute(
                      () -> {
                        runningTaskStarted.countDown();
                        releaseRunningTask.await();
                        return "running";
                      },
                      Duration.ofSeconds(5)));
      Future<String> queuedTask = null;

      try {
        assertThat(runningTaskStarted.await(1, TimeUnit.SECONDS)).isTrue();
        queuedTask = callers.submit(() -> executor.execute(() -> "queued", Duration.ofSeconds(5)));
        awaitQueuedWork(executor);

        assertThatThrownBy(() -> executor.execute(() -> "excess", Duration.ofSeconds(5)))
            .isInstanceOf(RejectedExecutionException.class);
        assertThat(executor.activeTaskCount()).isLessThanOrEqualTo(1);
        assertThat(executor.queuedTaskCount()).isLessThanOrEqualTo(1);

        releaseRunningTask.countDown();
        assertThat(runningTask.get(2, TimeUnit.SECONDS)).isEqualTo("running");
        assertThat(queuedTask.get(2, TimeUnit.SECONDS)).isEqualTo("queued");
      } finally {
        releaseRunningTask.countDown();
        callers.shutdownNow();
        assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void nonInterruptibleWorkRetainsItsBoundedWorkerSlotAfterCallerDeadline() throws Exception {
    try (BoundedFhirValidationExecutor executor = new BoundedFhirValidationExecutor(1)) {
      ExecutorService callers = Executors.newFixedThreadPool(2);
      CountDownLatch runningTaskStarted = new CountDownLatch(1);
      CountDownLatch runningTaskInterrupted = new CountDownLatch(1);
      CountDownLatch releaseRunningTask = new CountDownLatch(1);
      Future<String> timedOutRequest =
          callers.submit(
              () -> {
                try {
                  executor.execute(
                      () -> {
                        runningTaskStarted.countDown();
                        while (releaseRunningTask.getCount() > 0) {
                          try {
                            releaseRunningTask.await();
                          } catch (InterruptedException exception) {
                            runningTaskInterrupted.countDown();
                          }
                        }
                        return "finished after release";
                      },
                      Duration.ofMillis(100));
                  return "unexpected success";
                } catch (TimeoutException exception) {
                  return "caller deadline elapsed";
                }
              });
      Future<String> queuedRequest = null;

      try {
        assertThat(runningTaskStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(timedOutRequest.get(1, TimeUnit.SECONDS)).isEqualTo("caller deadline elapsed");
        assertThat(runningTaskInterrupted.await(1, TimeUnit.SECONDS)).isTrue();

        queuedRequest =
            callers.submit(() -> executor.execute(() -> "queued work", Duration.ofSeconds(5)));
        awaitQueuedWork(executor);

        assertThat(executor.activeTaskCount()).isEqualTo(1);
        assertThat(executor.queuedTaskCount()).isEqualTo(1);
        assertThatThrownBy(() -> executor.execute(() -> "excess", Duration.ofSeconds(1)))
            .isInstanceOf(RejectedExecutionException.class);

        releaseRunningTask.countDown();
        assertThat(queuedRequest.get(2, TimeUnit.SECONDS)).isEqualTo("queued work");
      } finally {
        releaseRunningTask.countDown();
        callers.shutdownNow();
        assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static void awaitQueuedWork(BoundedFhirValidationExecutor executor)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (executor.queuedTaskCount() == 0 && System.nanoTime() < deadline) {
      Thread.yield();
    }
    assertThat(executor.queuedTaskCount()).isEqualTo(1);
  }
}
