package org.ayuvahak.platform.notification.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.notification.application.NotificationStreamService;
import org.ayuvahak.platform.notification.configuration.NotificationStreamProperties;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Protected SSE endpoint carrying only owner-resource change hints. */
@RestController
@RequestMapping("/api/v1/notifications")
public final class NotificationStreamController {

  public static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  public static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  public static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  public static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";

  private final NotificationStreamService streams;
  private final NotificationStreamProperties properties;

  public NotificationStreamController(
      NotificationStreamService streams, NotificationStreamProperties properties) {
    this.streams = Objects.requireNonNull(streams, "streams");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> open(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      HttpServletRequest request) {
    if (!properties.isEnabled()) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.EVENT_DELIVERY_UNAVAILABLE,
          "The notification stream is disabled.");
    }
    if (!request.getParameterMap().isEmpty()) {
      throw malformed("Notification stream cursors and filters must not be placed in the URL.");
    }

    RequestedExecutionScope requestedScope = requestedScope(scope, organizationId, facilityId);
    Optional<String> lastEventId = lastEventId(request);
    CorrelationId correlationId = correlationId();
    SseEmitter emitter = new SseEmitter(properties.getMaximumLifetime().toMillis());
    streams.subscribe(
        actor,
        requestedScope,
        correlationId,
        lastEventId,
        new SseEmitterNotificationFeedSink(emitter));

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.TEXT_EVENT_STREAM);
    headers.setCacheControl("no-cache, no-transform");
    headers.set("X-Accel-Buffering", "no");
    return new ResponseEntity<>(emitter, headers, HttpStatus.OK);
  }

  private static RequestedExecutionScope requestedScope(
      String scope, String organizationId, String facilityId) {
    try {
      if ("ORGANIZATION".equals(scope) && organizationId != null && facilityId == null) {
        return RequestedExecutionScope.organization(
            directoryReference("Organization", organizationId));
      }
      if ("FACILITY".equals(scope) && organizationId != null && facilityId != null) {
        return RequestedExecutionScope.facility(
            directoryReference("Organization", organizationId),
            directoryReference("Facility", facilityId));
      }
    } catch (IllegalArgumentException malformed) {
      throw invalidScope();
    }
    throw invalidScope();
  }

  private static ExternalReference directoryReference(String resourceType, String value) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), resourceType, InternalId.parse(value).toString());
  }

  private static Optional<String> lastEventId(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(LAST_EVENT_ID_HEADER);
    if (values == null || !values.hasMoreElements()) {
      return Optional.empty();
    }
    String value = values.nextElement();
    if (values.hasMoreElements() || value == null || value.isBlank() || value.length() > 128) {
      throw malformed("A single bounded Last-Event-ID header is required.");
    }
    return Optional.of(value);
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "The notification stream requires one validated organization or facility scope.");
  }

  private static PlatformProblemException malformed(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.MALFORMED_REQUEST, detail);
  }
}
