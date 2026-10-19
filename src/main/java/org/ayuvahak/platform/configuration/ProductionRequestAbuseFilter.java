package org.ayuvahak.platform.configuration;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.authentication.ActorAuthentication;
import org.ayuvahak.platform.core.ActorReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Per-application-instance resource limits for production HTTP request classes. */
public final class ProductionRequestAbuseFilter extends OncePerRequestFilter {

  private static final long BUCKET_IDLE_NANOS = Duration.ofMinutes(2).toNanos();
  private static final int PRODUCTION_BUCKET_CAPACITY = 20_000;
  private static final String SSE_PATH = "/api/v1/notifications/stream";

  private final ProductionProfileProperties.Limits limits;
  private final int maximumBuckets;
  private final String processSalt = UUID.randomUUID().toString();
  private final Map<BucketKey, TokenBucket> buckets = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> sseConnections = new ConcurrentHashMap<>();
  private final AtomicLong requestsSinceCleanup = new AtomicLong();
  private final AtomicInteger totalSseConnections = new AtomicInteger();

  public ProductionRequestAbuseFilter(ProductionProfileProperties properties) {
    this(properties, PRODUCTION_BUCKET_CAPACITY);
  }

  ProductionRequestAbuseFilter(ProductionProfileProperties properties, int maximumBuckets) {
    this.limits = Objects.requireNonNull(properties, "properties").getLimits();
    if (maximumBuckets < 1) {
      throw new IllegalArgumentException("maximum rate-limit buckets must be positive");
    }
    this.maximumBuckets = maximumBuckets;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    if (request.getRequestURI().startsWith("/api/")) {
      response.setHeader("Cache-Control", "no-store");
    }
    long now = System.nanoTime();
    if ((requestsSinceCleanup.incrementAndGet() & 0xffL) == 0) {
      removeExpiredBuckets(now);
    }

    TrafficClass trafficClass = classify(request);
    String identity = requestIdentity(request);
    if (trafficClass == TrafficClass.SSE) {
      SseLease lease =
          reserveSse(
              identity, limits.getSseConnectionsPerPrincipal(), limits.getSseConnectionsTotal());
      if (lease == null) {
        writeRateLimited(response);
        return;
      }
      boolean listenerRegistered = false;
      try {
        filterChain.doFilter(request, response);
        if (request.isAsyncStarted()) {
          request.getAsyncContext().addListener(new SseCompletionListener(lease));
          listenerRegistered = true;
        }
      } finally {
        if (!listenerRegistered) {
          lease.close();
        }
      }
      return;
    }

    BucketKey key = new BucketKey(trafficClass, fingerprint(identity));
    if (!tryAcquire(key, requestsPerMinute(trafficClass), now)) {
      writeRateLimited(response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private boolean tryAcquire(BucketKey key, int limit, long now) {
    TokenBucket bucket = buckets.get(key);
    if (bucket == null) {
      synchronized (buckets) {
        bucket = buckets.get(key);
        if (bucket == null) {
          removeExpiredBuckets(now);
          if (buckets.size() >= maximumBuckets) {
            return false;
          }
          bucket = new TokenBucket(limit, now);
          buckets.put(key, bucket);
        }
      }
    }
    return bucket.tryAcquire(now);
  }

  private void removeExpiredBuckets(long now) {
    buckets
        .entrySet()
        .removeIf(entry -> now - entry.getValue().lastAccessNanos() > BUCKET_IDLE_NANOS);
  }

  private SseLease reserveSse(
      String identity, int maximumPerPrincipal, int maximumTotalConnections) {
    if (!reserveTotalSseConnection(maximumTotalConnections)) {
      return null;
    }
    String key = fingerprint(identity);
    AtomicBoolean reserved = new AtomicBoolean();
    sseConnections.compute(
        key,
        (ignored, current) -> {
          if (current == null) {
            current = new AtomicInteger();
          }
          if (current.get() >= maximumPerPrincipal) {
            return current;
          }
          current.incrementAndGet();
          reserved.set(true);
          return current;
        });
    if (!reserved.get()) {
      totalSseConnections.decrementAndGet();
      return null;
    }
    AtomicBoolean closed = new AtomicBoolean();
    return () -> {
      if (closed.compareAndSet(false, true)) {
        sseConnections.computeIfPresent(
            key,
            (ignored, current) -> {
              int remaining = current.decrementAndGet();
              return remaining <= 0 ? null : current;
            });
        totalSseConnections.decrementAndGet();
      }
    };
  }

  private boolean reserveTotalSseConnection(int maximumTotalConnections) {
    while (true) {
      int current = totalSseConnections.get();
      if (current >= maximumTotalConnections) {
        return false;
      }
      if (totalSseConnections.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  private String requestIdentity(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.isAuthenticated()) {
      Object principal = authentication.getPrincipal();
      if (principal instanceof ActorReference actor) {
        return "actor\u0000"
            + actor.kind().name()
            + "\u0000"
            + actor.identity().issuer().value()
            + "\u0000"
            + actor.identity().resourceType()
            + "\u0000"
            + actor.identity().value();
      }
      if (authentication instanceof ActorAuthentication) {
        // A malformed platform authentication must not collapse all actors into one quota.
        return "invalid-platform-actor\u0000" + UUID.randomUUID();
      }
      return "authenticated\u0000"
          + authentication.getClass().getName()
          + "\u0000"
          + authentication.getName();
    }
    // Forwarded headers are deliberately ignored; a proxy address is only a supplementary key.
    return "anonymous\u0000" + Objects.toString(request.getRemoteAddr(), "unknown");
  }

  private String fingerprint(String identity) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          digest.digest((processSalt + "\u0000" + identity).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 is required for in-memory rate-limit keys.", impossible);
    }
  }

  private int requestsPerMinute(TrafficClass trafficClass) {
    return switch (trafficClass) {
      case ANONYMOUS -> limits.getAnonymousRequestsPerMinute();
      case READ -> limits.getAuthenticatedReadRequestsPerMinute();
      case SENSITIVE_WRITE -> limits.getSensitiveWriteRequestsPerMinute();
      case ADMIN -> limits.getAdminRequestsPerMinute();
      case FEDERATION -> limits.getFederationRequestsPerMinute();
      case EXCHANGE -> limits.getExchangeRequestsPerMinute();
      case SSE -> throw new IllegalArgumentException("SSE uses a concurrent connection budget.");
    };
  }

  private static TrafficClass classify(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (SSE_PATH.equals(path)) {
      return TrafficClass.SSE;
    }
    if (path.startsWith("/api/federation/")) {
      return TrafficClass.FEDERATION;
    }
    if (path.startsWith("/api/v1/directory/mcsd/")
        || path.startsWith("/api/v1/transport-provider/")) {
      return TrafficClass.EXCHANGE;
    }
    if (path.startsWith("/api/v1/platform/") || path.startsWith("/actuator/")) {
      return TrafficClass.ADMIN;
    }
    if (!isAuthenticated()) {
      return TrafficClass.ANONYMOUS;
    }
    return switch (request.getMethod()) {
      case "GET", "HEAD", "OPTIONS" -> TrafficClass.READ;
      default -> TrafficClass.SENSITIVE_WRITE;
    };
  }

  private static boolean isAuthenticated() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null && authentication.isAuthenticated();
  }

  private static void writeRateLimited(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Retry-After", "1");
    String correlationId = response.getHeader(CorrelationIdFilter.HEADER_NAME);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = "unavailable";
    }
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Request capacity is temporarily exhausted.\","
                + "\"code\":\""
                + PlatformErrorCode.REQUEST_RATE_LIMITED.name()
                + "\",\"correlationId\":\""
                + correlationId
                + "\"}");
  }

  private static final class TokenBucket {

    private final int capacity;
    private double tokens;
    private long lastRefillNanos;
    private volatile long lastAccessNanos;

    private TokenBucket(int capacity, long now) {
      this.capacity = capacity;
      this.tokens = capacity;
      this.lastRefillNanos = now;
      this.lastAccessNanos = now;
    }

    private synchronized boolean tryAcquire(long now) {
      long elapsed = Math.max(0, now - lastRefillNanos);
      tokens =
          Math.min(
              capacity, tokens + ((double) elapsed * capacity / Duration.ofMinutes(1).toNanos()));
      lastRefillNanos = now;
      lastAccessNanos = now;
      if (tokens < 1.0) {
        return false;
      }
      tokens -= 1.0;
      return true;
    }

    private long lastAccessNanos() {
      return lastAccessNanos;
    }
  }

  private record BucketKey(TrafficClass trafficClass, String identityFingerprint) {}

  private enum TrafficClass {
    ANONYMOUS,
    READ,
    SENSITIVE_WRITE,
    ADMIN,
    SSE,
    FEDERATION,
    EXCHANGE
  }

  @FunctionalInterface
  private interface SseLease {
    void close();
  }

  private static final class SseCompletionListener implements AsyncListener {

    private final SseLease lease;

    private SseCompletionListener(SseLease lease) {
      this.lease = lease;
    }

    @Override
    public void onComplete(AsyncEvent event) {
      lease.close();
    }

    @Override
    public void onTimeout(AsyncEvent event) {
      lease.close();
    }

    @Override
    public void onError(AsyncEvent event) {
      lease.close();
    }

    @Override
    public void onStartAsync(AsyncEvent event) {
      event.getAsyncContext().addListener(this);
    }
  }
}
