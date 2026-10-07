package org.ayuvahak.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class SafeHttpRequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(SafeHttpRequestLoggingFilter.class);
  private static final Pattern SAFE_METHOD = Pattern.compile("[A-Z]{1,16}");
  private static final String UNMATCHED_ROUTE = "unmatched";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long startedAt = System.nanoTime();
    try {
      filterChain.doFilter(request, response);
    } finally {
      long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
      LOGGER
          .atInfo()
          .addKeyValue("event", "http_request")
          .addKeyValue("http.method", safeMethod(request.getMethod()))
          .addKeyValue("http.route", matchedRoute(request))
          .addKeyValue("http.status_code", response.getStatus())
          .addKeyValue("http.duration_ms", durationMillis)
          .log("Request completed");
    }
  }

  private static String safeMethod(String method) {
    return method != null && SAFE_METHOD.matcher(method).matches() ? method : "OTHER";
  }

  private static String matchedRoute(HttpServletRequest request) {
    Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return route instanceof String pathPattern && !pathPattern.isBlank()
        ? pathPattern
        : UNMATCHED_ROUTE;
  }
}
