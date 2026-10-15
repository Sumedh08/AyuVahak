package org.ayuvahak.platform.exchange.adapter.http;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;

/** Bounds the number and total duration of synchronous partner operations. */
final class BoundedPartnerOperationExecutor implements AutoCloseable {

  private final ThreadPoolExecutor workers;

  BoundedPartnerOperationExecutor(int workerCount, int queueCapacity) {
    if (workerCount < 1 || queueCapacity < 1) {
      throw new IllegalArgumentException("partner operation executor bounds must be positive");
    }
    workers =
        new ThreadPoolExecutor(
            workerCount,
            workerCount,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            workerThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  <T> T execute(Callable<T> operation, Duration timeout, Runnable cancel) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(cancel, "cancel");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("partner operation timeout must be positive");
    }
    Future<T> future;
    try {
      future = workers.submit(operation);
    } catch (RejectedExecutionException exception) {
      throw new PartnerTransportException(PartnerTransportFailure.CONNECTION_FAILURE);
    }
    try {
      return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      cancel(future, cancel);
      throw new PartnerTransportException(PartnerTransportFailure.OPERATION_TIMEOUT);
    } catch (InterruptedException exception) {
      cancel(future, cancel);
      Thread.currentThread().interrupt();
      throw new PartnerTransportException(PartnerTransportFailure.OPERATION_TIMEOUT);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof PartnerTransportException transportException) {
        throw transportException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new PartnerTransportException(PartnerTransportFailure.CONNECTION_FAILURE);
    }
  }

  private static void cancel(Future<?> future, Runnable callback) {
    try {
      callback.run();
    } finally {
      future.cancel(true);
    }
  }

  @Override
  public void close() {
    workers.shutdownNow();
  }

  private static ThreadFactory workerThreadFactory() {
    AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      Thread thread =
          new Thread(runnable, "ayuvahak-partner-exchange-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
