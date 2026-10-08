package org.ayuvahak.platform.audit.adapter.api;

import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditRecordPage;
import org.ayuvahak.platform.audit.application.AuditRecordView;
import org.ayuvahak.platform.audit.application.AuditReview;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Protected HTTP boundary for bounded, minimized audit chronology review. */
@RestController
@RequestMapping("/api/v1/platform/audit-records")
public class PlatformAuditReviewController {

  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";

  private final AuditReview auditReview;
  private final ExecutionContextFactory contextFactory;

  public PlatformAuditReviewController(
      AuditReview auditReview, ExecutionContextFactory contextFactory) {
    this.auditReview = auditReview;
    this.contextFactory = contextFactory;
  }

  @GetMapping
  public AuditCollection list(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor) {
    ExecutionContext context = context(actor, requestedScope);
    AuditRecordPage page = auditReview.list(context, Optional.ofNullable(cursor), limit);
    return new AuditCollection(page.items(), page.nextCursor());
  }

  private ExecutionContext context(ActorReference actor, String requestedScope) {
    CorrelationId correlationId = correlationId();
    if (!"PLATFORM".equals(requestedScope)) {
      auditReview.recordInvalidScope(actor, correlationId);
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "An explicit platform execution scope is required.");
    }
    if (actor.kind() == ActorKind.HUMAN) {
      return contextFactory.forInteractiveRequest(
          actor, RequestedExecutionScope.platform(), correlationId);
    }
    return contextFactory.forIntegrationRequest(
        actor, RequestedExecutionScope.platform(), correlationId);
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  public record AuditCollection(List<AuditRecordView> items, Optional<String> nextCursor) {
    public AuditCollection {
      items = List.copyOf(items);
    }
  }
}
