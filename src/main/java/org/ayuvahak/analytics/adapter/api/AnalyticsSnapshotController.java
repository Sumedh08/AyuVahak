package org.ayuvahak.analytics.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.ayuvahak.analytics.application.AnalyticsSnapshotPage;
import org.ayuvahak.analytics.application.AnalyticsSnapshotReader;
import org.ayuvahak.analytics.application.AnalyticsSnapshotSummary;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeDeniedException;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bounded, authorization-scoped read API for aggregate Analytics snapshots. */
@RestController
@RequestMapping("/api/v1/analytics/snapshots")
public final class AnalyticsSnapshotController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> PAGE_PARAMETERS = Set.of("page", "size");

  private final AnalyticsSnapshotReader reader;
  private final ExecutionContextFactory contextFactory;

  public AnalyticsSnapshotController(
      AnalyticsSnapshotReader reader, ExecutionContextFactory contextFactory) {
    this.reader = reader;
    this.contextFactory = contextFactory;
  }

  @GetMapping
  public SnapshotPageView list(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_HEADER, required = false) String facilityId,
      HttpServletRequest request) {
    validateParameters(request);
    AnalyticsSnapshotPage page =
        reader.list(
            executionContext(actor, scope, organizationId, facilityId),
            integerParameter(request, "page", 0),
            integerParameter(request, "size", 50));
    return new SnapshotPageView(
        page.items().stream().map(SnapshotView::from).toList(),
        page.page(),
        page.size(),
        page.hasMore());
  }

  private ExecutionContext executionContext(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    if (actor == null || actor.kind() != ActorKind.HUMAN) {
      throw problem(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified human actor is required.");
    }
    try {
      RequestedExecutionScope requested;
      if ("ORGANIZATION".equals(scope) && organizationId != null && facilityId == null) {
        requested =
            RequestedExecutionScope.organization(
                directoryReference("Organization", organizationId));
      } else if ("FACILITY".equals(scope) && organizationId != null && facilityId != null) {
        requested =
            RequestedExecutionScope.facility(
                directoryReference("Organization", organizationId),
                directoryReference("Facility", facilityId));
      } else {
        throw invalidScope();
      }
      return contextFactory.forInteractiveRequest(actor, requested, correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static ExternalReference directoryReference(String resourceType, String identifier) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), resourceType, InternalId.parse(identifier).toString());
  }

  private static void validateParameters(HttpServletRequest request) {
    if (request.getParameterMap().keySet().stream()
        .anyMatch(name -> !PAGE_PARAMETERS.contains(name))) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.VALIDATION_FAILED,
          "The Analytics snapshot view contains an unsupported filter.");
    }
    request
        .getParameterMap()
        .forEach(
            (name, values) -> {
              if (values.length != 1) {
                throw problem(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    PlatformErrorCode.VALIDATION_FAILED,
                    "An Analytics view parameter may be supplied only once.");
              }
            });
  }

  private static int integerParameter(HttpServletRequest request, String name, int defaultValue) {
    String[] values = request.getParameterMap().get(name);
    if (values == null) return defaultValue;
    try {
      return Integer.parseInt(values[0]);
    } catch (NumberFormatException malformed) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.VALIDATION_FAILED,
          "Analytics page parameters must be integers.");
    }
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalidScope() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "A validated Directory organization or facility scope is required.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  public record SnapshotPageView(List<SnapshotView> items, int page, int size, boolean hasMore) {
    public SnapshotPageView {
      items = List.copyOf(items);
    }
  }

  public record SnapshotView(
      java.util.UUID snapshotId,
      String indicatorKey,
      int indicatorVersion,
      java.time.LocalDate periodStart,
      java.time.LocalDate periodEndExclusive,
      String timeZone,
      String completeness,
      String value,
      java.time.Instant computedAt,
      Integer revision,
      String revisionState,
      String exportState,
      String exportResultClass) {

    private static SnapshotView from(AnalyticsSnapshotSummary summary) {
      return new SnapshotView(
          summary.snapshotId(),
          summary.indicatorKey(),
          summary.indicatorVersion(),
          summary.periodStart(),
          summary.periodEndExclusive(),
          summary.timeZone(),
          summary.completeness().name(),
          summary.value().map(java.math.BigDecimal::toPlainString).orElse(null),
          summary.computedAt(),
          summary.revision().orElse(null),
          summary.revisionState().orElse(null),
          summary.exportState().orElse(null),
          summary.exportResultClass().orElse(null));
    }
  }
}
