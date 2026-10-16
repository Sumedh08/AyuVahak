package org.ayuvahak.transport.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
import org.ayuvahak.transport.application.TransportRequestReadStore;
import org.ayuvahak.transport.application.TransportViews;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bounded Transport views nested under a Transfer participant authorization boundary. */
@RestController
@RequestMapping("/api/v1/transfers/{transferId}/transport-requests")
public final class TransportViewController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final Set<String> PAGE_PARAMETERS = Set.of("page", "size");

  private final TransportViews views;
  private final ExecutionContextFactory contexts;

  public TransportViewController(TransportViews views, ExecutionContextFactory contexts) {
    this.views = views;
    this.contexts = contexts;
  }

  @GetMapping
  public PageView<RequestView> requests(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      HttpServletRequest request) {
    validateParameters(request);
    var page =
        views.requests(
            executionContext(actor, scope, organizationId, facilityId),
            parseId(transferId, "Transfer id"),
            page(request),
            size(request));
    return new PageView<>(
        page.items().stream().map(RequestView::from).toList(),
        page.page(),
        page.size(),
        page.hasMore());
  }

  @GetMapping("/{requestId}")
  public DetailView detail(
      @PathVariable String transferId,
      @PathVariable String requestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      HttpServletRequest request) {
    validateParameters(request);
    return DetailView.from(
        views.detail(
            executionContext(actor, scope, organizationId, facilityId),
            parseId(transferId, "Transfer id"),
            parseId(requestId, "Transport request id")));
  }

  private ExecutionContext executionContext(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    if (actor == null) {
      throw new PlatformProblemException(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified actor is required.");
    }
    if (actor.kind() != ActorKind.HUMAN
        || !"FACILITY".equals(scope)
        || organizationId == null
        || facilityId == null) {
      throw invalidScope();
    }
    try {
      return contexts.forInteractiveRequest(
          actor,
          RequestedExecutionScope.facility(
              directoryReference("Organization", organizationId),
              directoryReference("Facility", facilityId)),
          correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static void validateParameters(HttpServletRequest request) {
    if (!PAGE_PARAMETERS.containsAll(request.getParameterMap().keySet())) {
      throw invalid("Transport views support only bounded page and size parameters.");
    }
  }

  private static int page(HttpServletRequest request) {
    return parsePageValue(request.getParameter("page"), 0, "page");
  }

  private static int size(HttpServletRequest request) {
    return parsePageValue(request.getParameter("size"), TransportViews.DEFAULT_PAGE_SIZE, "size");
  }

  private static int parsePageValue(String value, int fallback, String name) {
    if (value == null) return fallback;
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 0 || ("size".equals(name) && parsed == 0)) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException malformed) {
      throw invalid("Transport view " + name + " must be a bounded non-negative integer.");
    }
  }

  private static InternalId parseId(String value, String label) {
    try {
      return InternalId.parse(value);
    } catch (RuntimeException malformed) {
      throw invalid("A valid " + label + " is required.");
    }
  }

  private static ExternalReference directoryReference(String type, String value) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(value).toString());
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Transport views require an authorized human at a validated Facility scope.");
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSPORT_REQUEST_INVALID, detail);
  }

  public record PageView<T>(List<T> items, int page, int size, boolean hasMore) {
    public PageView {
      items = List.copyOf(items);
    }
  }

  public record RequestView(
      String transportRequestId,
      String transferId,
      String originFacilityId,
      String destinationFacilityId,
      String status,
      Instant requestedAt,
      String correlationId,
      long version) {
    static RequestView from(TransportRequestReadStore.RequestRow row) {
      return new RequestView(
          row.requestId().toString(),
          row.transferId().toString(),
          row.originFacilityId().toString(),
          row.destinationFacilityId().toString(),
          row.status().name(),
          row.requestedAt(),
          row.correlationId().value(),
          row.version());
    }
  }

  public record StatusView(
      String observationId,
      String sourceNamespace,
      String sourceObservationId,
      String status,
      Instant occurredAt,
      Instant recordedAt,
      String outcome,
      String requestStatusAfter,
      String reasonCode,
      long resultingRequestVersion) {
    static StatusView from(TransportRequestReadStore.StatusRow row) {
      return new StatusView(
          row.observationId().toString(),
          row.sourceNamespace(),
          row.sourceObservationId(),
          row.status().name(),
          row.occurredAt(),
          row.recordedAt(),
          row.outcome(),
          row.requestStatusAfter().name(),
          row.reasonCode().orElse(null),
          row.resultingRequestVersion());
    }
  }

  public record AssignmentView(
      String observationId,
      String sourceNamespace,
      String sourceObservationId,
      String assignmentReference,
      Instant occurredAt,
      Instant recordedAt,
      String outcome,
      String requestStatusAfter,
      long resultingRequestVersion) {
    static AssignmentView from(TransportRequestReadStore.AssignmentRow row) {
      return new AssignmentView(
          row.observationId().toString(),
          row.sourceNamespace(),
          row.sourceObservationId(),
          row.assignmentReference(),
          row.occurredAt(),
          row.recordedAt(),
          row.outcome(),
          row.requestStatusAfter().name(),
          row.resultingRequestVersion());
    }
  }

  public record EtaView(
      String observationId,
      String sourceNamespace,
      String sourceObservationId,
      Instant estimatedArrivalAt,
      Instant observedAt,
      Instant recordedAt,
      Instant validUntil) {
    static EtaView from(TransportRequestReadStore.EtaRow row) {
      return new EtaView(
          row.observationId().toString(),
          row.sourceNamespace(),
          row.sourceObservationId(),
          row.estimatedArrivalAt(),
          row.observedAt(),
          row.recordedAt(),
          row.validUntil());
    }
  }

  public record CancellationView(
      String operationId,
      String reasonCode,
      String state,
      String outcome,
      Instant initiatedAt,
      Instant resolvedAt,
      long version) {
    static CancellationView from(TransportRequestReadStore.CancellationRow row) {
      return new CancellationView(
          row.operationId().toString(),
          row.reasonCode(),
          row.state(),
          row.outcome().orElse(null),
          row.initiatedAt(),
          row.resolvedAt().orElse(null),
          row.version());
    }
  }

  public record DestinationChangeView(
      String operationId,
      String transferOperationId,
      String transferId,
      String oldFacilityId,
      String proposedFacilityId,
      String reasonCode,
      String state,
      String outcome,
      Instant createdAt,
      Instant updatedAt,
      long version,
      List<DestinationChangeEvidenceView> acknowledgementEvidence) {
    static DestinationChangeView from(TransportRequestReadStore.DestinationChangeRow row) {
      return new DestinationChangeView(
          row.operationId().toString(),
          row.transferOperationId().toString(),
          row.transferId().toString(),
          row.oldFacilityId().toString(),
          row.proposedFacilityId().toString(),
          row.reasonCode(),
          row.state(),
          row.outcome().orElse(null),
          row.createdAt(),
          row.updatedAt(),
          row.version(),
          row.evidence().stream().map(DestinationChangeEvidenceView::from).toList());
    }
  }

  public record DestinationChangeEvidenceView(
      String evidenceId,
      String reportedOutcome,
      String sourceNamespace,
      String sourceObservationId,
      Instant sourceOccurredAt,
      Instant recordedAt,
      String disposition,
      String reasonCode) {
    static DestinationChangeEvidenceView from(
        TransportRequestReadStore.DestinationChangeEvidenceRow row) {
      return new DestinationChangeEvidenceView(
          row.evidenceId().toString(),
          row.reportedOutcome(),
          row.sourceNamespace(),
          row.sourceObservationId(),
          row.sourceOccurredAt(),
          row.recordedAt(),
          row.disposition(),
          row.reasonCode().orElse(null));
    }
  }

  public record ProviderSubmissionView(
      String operationReference,
      String operationKind,
      String partnerKey,
      String state,
      Integer configurationVersion,
      Integer responseStatus,
      String failureCode,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    static ProviderSubmissionView from(TransportRequestReadStore.ProviderSubmissionRow row) {
      return new ProviderSubmissionView(
          row.operationReference().toString(),
          row.operationKind(),
          row.partnerKey(),
          row.state(),
          row.configurationVersion().orElse(null),
          row.responseStatus().orElse(null),
          row.failureCode().orElse(null),
          row.createdAt(),
          row.updatedAt(),
          row.version());
    }
  }

  public record DetailView(
      RequestView request,
      List<StatusView> statusObservations,
      List<AssignmentView> assignmentObservations,
      List<EtaView> etaObservations,
      List<CancellationView> cancellationOperations,
      List<DestinationChangeView> destinationChangeOperations,
      List<ProviderSubmissionView> providerSubmissions) {
    public DetailView {
      statusObservations = List.copyOf(statusObservations);
      assignmentObservations = List.copyOf(assignmentObservations);
      etaObservations = List.copyOf(etaObservations);
      cancellationOperations = List.copyOf(cancellationOperations);
      destinationChangeOperations = List.copyOf(destinationChangeOperations);
      providerSubmissions = List.copyOf(providerSubmissions);
    }

    static DetailView from(TransportViews.Detail detail) {
      return new DetailView(
          RequestView.from(detail.request()),
          detail.statuses().stream().map(StatusView::from).toList(),
          detail.assignments().stream().map(AssignmentView::from).toList(),
          detail.etas().stream().map(EtaView::from).toList(),
          detail.cancellations().stream().map(CancellationView::from).toList(),
          detail.destinationChanges().stream().map(DestinationChangeView::from).toList(),
          detail.providerSubmissions().stream().map(ProviderSubmissionView::from).toList());
    }
  }
}
