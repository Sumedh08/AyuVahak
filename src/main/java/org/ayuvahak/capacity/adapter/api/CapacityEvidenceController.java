package org.ayuvahak.capacity.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.capacity.application.CapacityEvidenceQueries;
import org.ayuvahak.capacity.application.CapacityObservationHistoryPage;
import org.ayuvahak.capacity.domain.CapacityEvidenceProjection;
import org.ayuvahak.capacity.domain.CapacityObservation;
import org.ayuvahak.capacity.domain.CapacityObservationFact;
import org.ayuvahak.directory.application.DirectoryScopeLookupUnavailableException;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected current-evidence and bounded-history query boundary for Capacity. */
@RestController
@RequestMapping("/api/v1/capacity/facilities/{facilityId}")
public final class CapacityEvidenceController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> CURRENT_QUERY_FIELDS = Set.of("dimension", "at");
  private static final Set<String> HISTORY_QUERY_FIELDS =
      Set.of("dimension", "sourceCode", "from", "until", "page", "size");

  private final CapacityEvidenceQueries queries;
  private final ExecutionContextFactory contextFactory;

  public CapacityEvidenceController(
      CapacityEvidenceQueries queries, ExecutionContextFactory contextFactory) {
    this.queries = queries;
    this.contextFactory = contextFactory;
  }

  @GetMapping("/current")
  public CurrentEvidenceCollection current(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScopeId,
      @PathVariable String facilityId,
      HttpServletRequest request) {
    rejectUnknownParameters(request, CURRENT_QUERY_FIELDS);
    List<String> dimensions =
        Optional.ofNullable(request.getParameterValues("dimension"))
            .map(Arrays::asList)
            .orElse(List.of());
    Optional<Instant> at = instantParameter(request, "at");
    ExternalReference facility = facilityReference(facilityId);
    ExecutionContext context = context(actor, scope, organizationId, facilityScopeId, facilityId);
    Instant evaluatedAt = at.orElseGet(Instant::now);
    List<CapacityEvidenceProjection> projections =
        queries.current(context, facility, dimensions, Optional.of(evaluatedAt));
    return new CurrentEvidenceCollection(
        facility.value(), evaluatedAt, projections.stream().map(CurrentEvidence::from).toList());
  }

  @GetMapping("/observations")
  public ObservationHistoryCollection history(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScopeId,
      @PathVariable String facilityId,
      HttpServletRequest request) {
    rejectUnknownParameters(request, HISTORY_QUERY_FIELDS);
    Optional<String> dimension = singleParameter(request, "dimension");
    Optional<String> source = singleParameter(request, "sourceCode");
    Optional<Instant> from = instantParameter(request, "from");
    Optional<Instant> until = instantParameter(request, "until");
    int page = integerParameter(request, "page", 0);
    int size = integerParameter(request, "size", 50);
    ExternalReference facility = facilityReference(facilityId);
    ExecutionContext context = context(actor, scope, organizationId, facilityScopeId, facilityId);
    CapacityObservationHistoryPage result =
        queries.history(context, facility, dimension, source, from, until, page, size);
    return new ObservationHistoryCollection(
        result.items().stream().map(ObservationHistoryItem::from).toList(),
        result.page(),
        result.size(),
        result.hasMore());
  }

  private ExecutionContext context(
      ActorReference actor,
      String scope,
      String organizationId,
      String facilityScopeId,
      String facilityId) {
    CorrelationId correlationId = correlationId();
    if (!"FACILITY".equals(scope)
        || organizationId == null
        || facilityScopeId == null
        || !facilityId.equals(facilityScopeId)) {
      queries.recordInvalidScope(actor, correlationId);
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Capacity evidence queries require explicit matching Facility execution scope.");
    }
    try {
      RequestedExecutionScope requested =
          RequestedExecutionScope.facility(
              directoryReference("Organization", organizationId),
              directoryReference("Facility", facilityScopeId));
      return actor.kind() == ActorKind.HUMAN
          ? contextFactory.forInteractiveRequest(actor, requested, correlationId)
          : contextFactory.forIntegrationRequest(actor, requested, correlationId);
    } catch (DirectoryScopeLookupUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.CAPACITY_UNAVAILABLE,
          "Directory could not validate the Facility execution scope.");
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      queries.recordInvalidScope(actor, correlationId);
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "The requested Capacity Facility scope is not active or valid.");
    }
  }

  private static void rejectUnknownParameters(HttpServletRequest request, Set<String> allowed) {
    if (request.getParameterMap().keySet().stream().anyMatch(key -> !allowed.contains(key))) {
      throw invalid("The Capacity evidence query contains an unsupported filter.");
    }
  }

  private static Optional<String> singleParameter(HttpServletRequest request, String name) {
    String[] values = request.getParameterValues(name);
    if (values == null || values.length == 0) {
      return Optional.empty();
    }
    if (values.length != 1 || values[0].isBlank()) {
      throw invalid("The " + name + " query parameter must be supplied once.");
    }
    return Optional.of(values[0]);
  }

  private static Optional<Instant> instantParameter(HttpServletRequest request, String name) {
    return singleParameter(request, name)
        .map(
            value -> {
              try {
                return Instant.parse(value);
              } catch (RuntimeException malformed) {
                throw invalid("The " + name + " query parameter must be an ISO-8601 instant.");
              }
            });
  }

  private static int integerParameter(HttpServletRequest request, String name, int defaultValue) {
    return singleParameter(request, name)
        .map(
            value -> {
              try {
                return Integer.parseInt(value);
              } catch (NumberFormatException malformed) {
                throw invalid("The " + name + " query parameter must be an integer.");
              }
            })
        .orElse(defaultValue);
  }

  private static ExternalReference facilityReference(String identifier) {
    try {
      return directoryReference("Facility", identifier);
    } catch (IllegalArgumentException malformed) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.NOT_FOUND,
          "The Capacity Facility does not exist.");
    }
  }

  private static ExternalReference directoryReference(String type, String identifier) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(identifier).toString());
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.CAPACITY_VALUE_INVALID, detail);
  }

  public record CurrentEvidenceCollection(
      String facilityId, Instant evaluatedAt, List<CurrentEvidence> dimensions) {
    public CurrentEvidenceCollection {
      dimensions = List.copyOf(dimensions);
    }
  }

  public record CurrentEvidence(
      String dimensionCode,
      String freshness,
      Long dimensionVersion,
      Long policyVersion,
      String projectionVersion,
      String valueKind,
      BigDecimal quantity,
      String state,
      String unit,
      List<ObservationEvidence> evidence,
      boolean fallbackUsed,
      List<String> reasonCodes) {
    public CurrentEvidence {
      evidence = List.copyOf(evidence);
      reasonCodes = List.copyOf(reasonCodes);
    }

    static CurrentEvidence from(CapacityEvidenceProjection projection) {
      return new CurrentEvidence(
          projection.dimensionCode(),
          projection.freshness().name(),
          projection.dimensionVersion().isPresent()
              ? projection.dimensionVersion().getAsLong()
              : null,
          projection.policyVersion().isPresent() ? projection.policyVersion().getAsLong() : null,
          projection.projectionVersion(),
          projection.valueKind().map(Enum::name).orElse(null),
          projection.quantity().orElse(null),
          projection.state().orElse(null),
          projection.unit().orElse(null),
          projection.evidence().stream().map(ObservationEvidence::from).toList(),
          projection.fallbackUsed(),
          projection.reasonCodes());
    }
  }

  public record ObservationHistoryCollection(
      List<ObservationHistoryItem> items, int page, int size, boolean hasMore) {
    public ObservationHistoryCollection {
      items = List.copyOf(items);
    }
  }

  public record ObservationHistoryItem(ObservationEvidence observation, boolean superseded) {
    static ObservationHistoryItem from(CapacityObservationFact fact) {
      return new ObservationHistoryItem(
          ObservationEvidence.from(fact.observation()), fact.superseded());
    }
  }

  public record ObservationEvidence(
      String observationId,
      String dimensionCode,
      long dimensionVersion,
      String kind,
      String valueKind,
      BigDecimal quantity,
      String state,
      String unit,
      String sourceCode,
      ExternalReference sourceObservation,
      String reportingMethod,
      ActorReference reportedBy,
      Instant observedAt,
      Instant receivedAt,
      Instant sourceValidUntil,
      Instant validUntil,
      long sourcePolicyVersion,
      boolean futureSkewed,
      String reasonCode,
      String supersedesObservationId) {
    static ObservationEvidence from(CapacityObservation observation) {
      return new ObservationEvidence(
          observation.id().toString(),
          observation.dimensionCode(),
          observation.dimensionVersion(),
          observation.kind().name(),
          observation.valueKind().map(Enum::name).orElse(null),
          observation.quantity().orElse(null),
          observation.state().orElse(null),
          observation.unit().orElse(null),
          observation.sourceCode(),
          observation.sourceObservation(),
          observation.reportingMethod().name(),
          observation.reportedBy(),
          observation.observedAt(),
          observation.receivedAt(),
          observation.sourceValidUntil().orElse(null),
          observation.validUntil().orElse(null),
          observation.policyVersion(),
          observation.futureSkewed(),
          observation.reasonCode(),
          observation.supersedesObservationId().map(InternalId::toString).orElse(null));
    }
  }
}
