package org.ayuvahak.platform.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER_NAME = "X-Correlation-ID";
  public static final String MDC_KEY = "correlationId";

  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = readSafeCorrelationId(request);
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }

    response.setHeader(HEADER_NAME, correlationId);
    MDC.put(MDC_KEY, correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private String readSafeCorrelationId(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(HEADER_NAME);
    if (values == null || !values.hasMoreElements()) {
      return null;
    }

    String value = values.nextElement();
    if (values.hasMoreElements() || !SAFE_ID.matcher(value).matches()) {
      return null;
    }
    return value;
  }
}
