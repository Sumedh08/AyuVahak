package org.ayuvahak.platform.exchange.adapter.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Performs OS DNS lookups in a bounded, deadline-limited worker pool. */
final class BoundedDnsAddressLookup implements VettedDnsResolver.AddressLookup, AutoCloseable {

  private static final int WORKER_COUNT = 4;
  private static final int QUEUE_CAPACITY = 16;

  private final VettedDnsResolver.AddressLookup delegate;
  private final ThreadPoolExecutor workers;

  BoundedDnsAddressLookup() {
    this((hostname, timeout) -> InetAddress.getAllByName(hostname));
  }

  BoundedDnsAddressLookup(VettedDnsResolver.AddressLookup delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.workers =
        new ThreadPoolExecutor(
            WORKER_COUNT,
            WORKER_COUNT,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            dnsThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  @Override
  public InetAddress[] lookup(String hostname, Duration timeout) throws UnknownHostException {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("DNS timeout must be positive");
    }
    Future<InetAddress[]> lookup;
    try {
      lookup = workers.submit(() -> delegate.lookup(hostname, timeout));
    } catch (RejectedExecutionException exception) {
      throw new DnsResolutionFailure(DnsResolutionFailure.Reason.CAPACITY_EXCEEDED);
    }
    try {
      return lookup.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      lookup.cancel(true);
      throw new DnsResolutionFailure(DnsResolutionFailure.Reason.TIMED_OUT);
    } catch (InterruptedException exception) {
      lookup.cancel(true);
      Thread.currentThread().interrupt();
      throw new DnsResolutionFailure(DnsResolutionFailure.Reason.TIMED_OUT);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof UnknownHostException unknownHostException) {
        throw unknownHostException;
      }
      throw new DnsResolutionFailure(DnsResolutionFailure.Reason.FAILED);
    }
  }

  @Override
  public void close() {
    workers.shutdownNow();
  }

  private static ThreadFactory dnsThreadFactory() {
    AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "ayuvahak-partner-dns-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
