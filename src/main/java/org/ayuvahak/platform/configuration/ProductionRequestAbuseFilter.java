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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.authentication.ActorAuthentication;
import org.ayuvahak.platform.core.ActorReference;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies bounded production request budgets before authorization and business handling. */
public final class ProductionRequestAbuseFilter extends OncePerRequestFilter {

  private static final String SSE_PATH = "/api/v1/notifications/stream";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final int MAX_SCOPE_HEADER_LENGTH = 128;

  private final ProductionProfileProperties.Limits limits;
  private final RequestBudgetStore budgetStore;
  // These are deliberately node-local transport ceilings, not deployment-wide quotas.
  private final ConcurrentHashMap<String, AtomicInteger> sseConnections = new ConcurrentHashMap<>();
  private final AtomicInteger totalSseConnections = new AtomicInteger();

  public ProductionRequestAbuseFilter(
      ProductionProfileProperties properties, RequestBudgetStore budgetStore) {
    this.limits = Objects.requireNonNull(properties, "properties").getLimits();
    this.budgetStore = Objects.requireNonNull(budgetStore, "request budget store");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    if (request.getRequestURI().startsWith("/api/")) {
      response.setHeader("Cache-Control", "no-store");
    }

    TrafficClass trafficClass = classify(request);
    String principalIdentity = requestIdentity(request);
    List<RequestBudgetStore.Charge> charges = charges(request, principalIdentity, trafficClass);
    final boolean allowed;
    try {
      allowed = budgetStore.tryAcquire(charges);
    } catch (DataAccessException | TransactionException unavailable) {
      writeLimiterUnavailable(response);
      return;
    }
    if (!allowed) {
      writeRateLimited(response);
      return;
    }

    if (trafficClass == TrafficClass.SSE) {
      SseLease lease =
          reserveSse(
              principalIdentity,
              limits.getSseConnectionsPerPrincipal(),
              limits.getSseConnectionsTotal());
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
    filterChain.doFilter(request, response);
  }

  private List<RequestBudgetStore.Charge> charges(
      HttpServletRequest request, String principalIdentity, TrafficClass trafficClass) {
    List<RequestBudgetStore.Charge> result = new ArrayList<>(4);
    result.add(
        new RequestBudgetStore.Charge(
            RequestBudgetStore.Dimension.PRINCIPAL,
            "ALL",
            principalIdentity,
            limits.getPrincipalRequestsPerMinute()));
    scopeIdentity(request, principalIdentity)
        .ifPresent(
            scope ->
                result.add(
                    new RequestBudgetStore.Charge(
                        RequestBudgetStore.Dimension.SCOPE,
                        "ALL",
                        scope,
                        limits.getScopeRequestsPerMinute())));
    serviceIdentity(principalIdentity)
        .filter(
            ignored ->
                trafficClass == TrafficClass.FEDERATION || trafficClass == TrafficClass.EXCHANGE)
        .ifPresent(
            partner ->
                result.add(
                    new RequestBudgetStore.Charge(
                        RequestBudgetStore.Dimension.PARTNER,
                        "ALL",
                        partner,
                        limits.getPartnerRequestsPerMinute())));
    result.add(
        new RequestBudgetStore.Charge(
            RequestBudgetStore.Dimension.OPERATION,
            trafficClass.name(),
            principalIdentity,
            requestsPerMinute(trafficClass)));
    return List.copyOf(result);
  }

  private static java.util.Optional<String> scopeIdentity(
      HttpServletRequest request, String principalIdentity) {
    if (!(principalIdentity.startsWith("human\u0000")
        || principalIdentity.startsWith("service\u0000"))) {
      return java.util.Optional.empty();
    }
    // Requested scopes only add a fair-share key; normal grant checks still decide access.
    String scopeKind = safeScopePart(request.getHeader(EXECUTION_SCOPE_HEADER));
    if (scopeKind == null) {
      return java.util.Optional.empty();
    }
    String organization = safeScopePart(request.getHeader(ORGANIZATION_SCOPE_HEADER));
    String facility = safeScopePart(request.getHeader(FACILITY_SCOPE_HEADER));
    if ("ORGANIZATION".equals(scopeKind) && organization != null && facility == null) {
      return java.util.Optional.of("ORGANIZATION\u0000" + organization);
    }
    if ("FACILITY".equals(scopeKind) && organization != null && facility != null) {
      return java.util.Optional.of("FACILITY\u0000" + organization + "\u0000" + facility);
    }
    // Platform, malformed, or incomplete scopes do not acquire a fabricated tenant bucket.
    return java.util.Optional.empty();
  }

  private static String safeScopePart(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_SCOPE_HEADER_LENGTH) {
      return null;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!(Character.isLetterOrDigit(character)
          || character == '-'
          || character == '_'
          || character == '.'
          || character == ':')) {
        return null;
      }
    }
    return value;
  }

  private static java.util.Optional<String> serviceIdentity(String principalIdentity) {
    return principalIdentity.startsWith("service\u0000")
        ? java.util.Optional.of(principalIdentity)
        : java.util.Optional.empty();
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
        String identity =
            actor.kind().name().toLowerCase(Locale.ROOT)
                + "\u0000"
                + actor.identity().issuer().value()
                + "\u0000"
                + actor.identity().resourceType()
                + "\u0000"
                + actor.identity().value();
        return identity;
      }
      if (authentication instanceof ActorAuthentication) {
        // Malformed platform authentication cannot evade the limiter by changing source address.
        return "invalid-platform-actor";
      }
      return "authenticated\u0000"
          + authentication.getClass().getName()
          + "\u0000"
          + authentication.getName();
    }
    // Forwarded headers are deliberately ignored; proxy address is only an anonymous key.
    return "anonymous\u0000" + Objects.toString(request.getRemoteAddr(), "unknown");
  }

  private static String fingerprint(String identity) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required for in-memory SSE keys.", impossible);
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
      case SSE -> limits.getSseSubscriptionsPerMinute();
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
    writeProblem(
        response,
        HttpStatus.TOO_MANY_REQUESTS,
        PlatformErrorCode.REQUEST_RATE_LIMITED,
        "Too Many Requests",
        "Request capacity is temporarily exhausted.");
    response.setHeader("Retry-After", "1");
  }

  private static void writeLimiterUnavailable(HttpServletResponse response) throws IOException {
    writeProblem(
        response,
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.REQUEST_RATE_LIMITER_UNAVAILABLE,
        "Request capacity check unavailable",
        "The request was not admitted because the shared request budget could not be checked.");
    response.setHeader("Retry-After", "1");
  }

  private static void writeProblem(
      HttpServletResponse response,
      HttpStatus status,
      PlatformErrorCode code,
      String title,
      String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader("Cache-Control", "no-store");
    String correlationId = response.getHeader(CorrelationIdFilter.HEADER_NAME);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = "unavailable";
    }
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\""
                + title
                + "\",\"status\":"
                + status.value()
                + ",\"detail\":\""
                + detail
                + "\",\"code\":\""
                + code.name()
                + "\",\"correlationId\":\""
                + correlationId
                + "\"}");
  }

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
