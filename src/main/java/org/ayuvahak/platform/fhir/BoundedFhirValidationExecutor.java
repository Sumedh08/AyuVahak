package org.ayuvahak.platform.fhir;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs FHIR model parsing and validation with bounded concurrency, queueing, and caller wait time.
 *
 * <p>One worker is an intentional initial throughput limit: the configured HAPI validator is shared
 * and serialized, so another worker would not increase validation throughput. A deadline cancels
 * the future and interrupts its worker, but a non-interruptible HAPI operation can retain that
 * worker until HAPI returns. Its slot and the fixed queue remain bounded; later work is rejected
 * explicitly instead of creating more threads or unbounded backlog. Increasing throughput requires
 * measured need and validator isolation/thread-safety evidence.
 */
final class BoundedFhirValidationExecutor implements AutoCloseable {

  private static final int MAXIMUM_QUEUE_CAPACITY = 256;
  private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

  private final ThreadPoolExecutor executor;

  BoundedFhirValidationExecutor(int queueCapacity) {
    if (queueCapacity < 1 || queueCapacity > MAXIMUM_QUEUE_CAPACITY) {
      throw new IllegalArgumentException(
          "FHIR validation queue capacity is outside the allowed range");
    }
    ThreadFactory threadFactory =
        task -> {
          Thread thread =
              new Thread(task, "ayu-vahak-fhir-validation-" + THREAD_SEQUENCE.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    executor =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy());
  }

  <T> T execute(Callable<T> task, Duration timeout)
      throws InterruptedException, ExecutionException, TimeoutException {
    Objects.requireNonNull(task);
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new TimeoutException("FHIR validation time budget is exhausted");
    }

    FutureTask<T> future = new FutureTask<>(task);
    try {
      executor.execute(future);
    } catch (RejectedExecutionException exception) {
      throw exception;
    }
    try {
      return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException | TimeoutException exception) {
      future.cancel(true);
      executor.remove(future);
      throw exception;
    }
  }

  int activeTaskCount() {
    return executor.getActiveCount();
  }

  int queuedTaskCount() {
    return executor.getQueue().size();
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
