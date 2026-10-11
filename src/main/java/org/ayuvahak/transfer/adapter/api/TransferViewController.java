package org.ayuvahak.transfer.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.ayuvahak.transfer.application.TransferViewStore.IncomingRequest;
import org.ayuvahak.transfer.application.TransferViewStore.RequestAttemptView;
import org.ayuvahak.transfer.application.TransferViewStore.TransferSummary;
import org.ayuvahak.transfer.application.TransferViews;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Participant-scoped, bounded Transfer read endpoints. */
@RestController
@RequestMapping("/api/v1")
public final class TransferViewController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> PAGE_PARAMETERS = Set.of("page", "size");
  private static final Set<String> INCOMING_PARAMETERS = Set.of("page", "size", "state");
  private static final Set<String> TRANSFER_PARAMETERS = Set.of("page", "size", "status");

  private final TransferViews views;
  private final ExecutionContextFactory contextFactory;

  public TransferViewController(TransferViews views, ExecutionContextFactory contextFactory) {
    this.views = views;
    this.contextFactory = contextFactory;
  }

  @GetMapping("/transfer-journeys/{journeyId}")
  public JourneyView journey(
      @PathVariable String journeyId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      HttpServletRequest request) {
    validateParameters(request, PAGE_PARAMETERS);
    var page =
        views.journey(
            executionContext(actor, scope, organizationId, facilityId),
            parseId(journeyId, "TransferJourney id"),
            page(request),
            size(request));
    return JourneyView.from(page);
  }

  @GetMapping("/transfer-request-attempts")
  public PageView<IncomingRequestView> pendingIncoming(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      HttpServletRequest request) {
    validateParameters(request, INCOMING_PARAMETERS);
    String state = singleParameter(request, "state");
    if (state != null && !"PENDING".equals(state)) {
      throw invalid("Incoming Transfer requests support only the PENDING state.");
    }
    var page =
        views.pendingIncoming(
            executionContext(actor, scope, organizationId, facilityId),
            page(request),
            size(request));
    return new PageView<>(
        page.items().stream().map(IncomingRequestView::from).toList(),
        page.page(),
        page.size(),
        page.hasMore());
  }

  @GetMapping("/transfers")
  public PageView<TransferView> transfers(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      HttpServletRequest request) {
    validateParameters(request, TRANSFER_PARAMETERS);
    Optional<CoordinatedTransfer.Status> status =
        transferStatus(singleParameter(request, "status"));
    var page =
        views.transfers(
            executionContext(actor, scope, organizationId, facilityId),
            status,
            page(request),
            size(request));
    return new PageView<>(
        page.items().stream().map(TransferView::from).toList(),
        page.page(),
        page.size(),
        page.hasMore());
  }

  @GetMapping("/transfers/{transferId}")
  public TransferView transfer(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      HttpServletRequest request) {
    validateParameters(request, Set.of());
    return TransferView.from(
        views.transfer(
            executionContext(actor, scope, organizationId, facilityId),
            parseId(transferId, "Transfer id")));
  }

  private ExecutionContext executionContext(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    if (actor == null) {
      throw new PlatformProblemException(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified actor is required.");
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
      return actor.kind() == ActorKind.HUMAN
          ? contextFactory.forInteractiveRequest(actor, requested, correlationId())
          : contextFactory.forIntegrationRequest(actor, requested, correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static ExternalReference directoryReference(String type, String value) {
    try {
      return new ExternalReference(
          new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(value).toString());
    } catch (IllegalArgumentException malformed) {
      throw invalidScope();
    }
  }

  private static Optional<CoordinatedTransfer.Status> transferStatus(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(CoordinatedTransfer.Status.valueOf(value));
    } catch (IllegalArgumentException invalid) {
      throw invalid("The Transfer status filter is not supported.");
    }
  }

  private static int page(HttpServletRequest request) {
    String value = singleParameter(request, "page");
    if (value == null) {
      return 0;
    }
    try {
      int page = Integer.parseInt(value);
      if (page < 0) {
        throw new NumberFormatException();
      }
      return page;
    } catch (NumberFormatException malformed) {
      throw invalid("The page parameter must be a non-negative integer.");
    }
  }

  private static int size(HttpServletRequest request) {
    String value = singleParameter(request, "size");
    if (value == null) {
      return TransferViews.DEFAULT_PAGE_SIZE;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException malformed) {
      throw invalid("The size parameter must be an integer between 1 and 100.");
    }
  }

  private static void validateParameters(HttpServletRequest request, Set<String> allowed) {
    if (request.getParameterMap().keySet().stream().anyMatch(name -> !allowed.contains(name))) {
      throw invalid("The Transfer view contains an unsupported filter.");
    }
    request
        .getParameterMap()
        .forEach(
            (name, values) -> {
              if (values.length != 1) {
                throw invalid("A Transfer view parameter may be supplied only once.");
              }
            });
  }

  private static String singleParameter(HttpServletRequest request, String name) {
    String[] values = request.getParameterMap().get(name);
    return values == null ? null : values[0];
  }

  private static InternalId parseId(String value, String label) {
    try {
      return InternalId.parse(value);
    } catch (IllegalArgumentException malformed) {
      throw invalid("A valid " + label + " is required.");
    }
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(org.ayuvahak.platform.api.CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSFER_REQUEST_INVALID, detail);
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Transfer views require a validated Directory organization or Facility scope.");
  }

  public record PageView<T>(List<T> items, int page, int size, boolean hasMore) {
    public PageView {
      items = List.copyOf(items);
    }
  }

  public record JourneyView(
      String journeyId,
      String referralId,
      String originOrganizationId,
      String originFacilityId,
      String createdAt,
      long version,
      List<RequestAttemptRepresentation> items,
      int page,
      int size,
      boolean hasMore) {
    static JourneyView from(TransferViews.JourneyPage page) {
      return new JourneyView(
          page.journeyId().toString(),
          page.referralId().toString(),
          page.originOrganizationId().toString(),
          page.originFacilityId().map(InternalId::toString).orElse(null),
          page.createdAt().toString(),
          page.version(),
          page.items().stream().map(RequestAttemptRepresentation::from).toList(),
          page.page(),
          page.size(),
          page.hasMore());
    }
  }

  public record RequestAttemptRepresentation(
      String requestAttemptId,
      int attemptNumber,
      String referralId,
      long referralRevision,
      String recommendationId,
      String destinationFacilityId,
      String status,
      String requestedAt,
      String expiresAt,
      String terminalOccurredAt,
      String terminalRecordedAt,
      String clinicalOutcome,
      String clinicalReasonCode,
      String operationalOutcome,
      String operationalReasonCode,
      String decisionKind,
      String decisionReasonCode,
      String transferId,
      String transferStatus,
      String reservationOutcome,
      String reservationId) {
    static RequestAttemptRepresentation from(RequestAttemptView attempt) {
      return new RequestAttemptRepresentation(
          attempt.requestAttemptId().toString(),
          attempt.attemptNumber(),
          attempt.referralId().toString(),
          attempt.referralRevision(),
          attempt.recommendationId().toString(),
          attempt.destinationFacilityId().toString(),
          attempt.status().name(),
          attempt.requestedAt().toString(),
          attempt.expiresAt().toString(),
          attempt.terminalOccurredAt().map(Instant::toString).orElse(null),
          attempt.terminalRecordedAt().map(Instant::toString).orElse(null),
          attempt.clinicalOutcome().orElse(null),
          attempt.clinicalReasonCode().orElse(null),
          attempt.operationalOutcome().orElse(null),
          attempt.operationalReasonCode().orElse(null),
          attempt.decisionKind().orElse(null),
          attempt.decisionReasonCode().orElse(null),
          attempt.transferId().map(InternalId::toString).orElse(null),
          attempt.transferStatus().orElse(null),
          attempt.reservationOutcome().orElse(null),
          attempt.reservationId().map(InternalId::toString).orElse(null));
    }
  }

  public record IncomingRequestView(
      String requestAttemptId,
      String journeyId,
      String referralId,
      long referralRevision,
      String recommendationId,
      String originOrganizationId,
      String originFacilityId,
      String receivingFacilityId,
      String requestedAt,
      String expiresAt) {
    static IncomingRequestView from(IncomingRequest request) {
      return new IncomingRequestView(
          request.requestAttemptId().toString(),
          request.journeyId().toString(),
          request.referralId().toString(),
          request.referralRevision(),
          request.recommendationId().toString(),
          request.originOrganizationId().toString(),
          request.originFacilityId().map(InternalId::toString).orElse(null),
          request.receivingFacilityId().toString(),
          request.requestedAt().toString(),
          request.expiresAt().toString());
    }
  }

  public record TransferView(
      String transferId,
      String journeyId,
      String requestAttemptId,
      String referralId,
      String recommendationId,
      String originOrganizationId,
      String originFacilityId,
      String receivingOrganizationId,
      String receivingFacilityId,
      String status,
      long version,
      String createdAt,
      String lastEventAt,
      long lastAggregateSequence,
      String reservationOutcome,
      String reservationId) {
    static TransferView from(TransferSummary transfer) {
      return new TransferView(
          transfer.transferId().toString(),
          transfer.journeyId().toString(),
          transfer.requestAttemptId().toString(),
          transfer.referralId().toString(),
          transfer.recommendationId().toString(),
          transfer.originOrganizationId().toString(),
          transfer.originFacilityId().map(InternalId::toString).orElse(null),
          transfer.receivingOrganizationId().toString(),
          transfer.receivingFacilityId().toString(),
          transfer.status().name(),
          transfer.version(),
          transfer.createdAt().toString(),
          transfer.lastEventAt().toString(),
          transfer.lastAggregateSequence(),
          transfer.reservationOutcome().orElse(null),
          transfer.reservationId().map(InternalId::toString).orElse(null));
    }
  }
}
