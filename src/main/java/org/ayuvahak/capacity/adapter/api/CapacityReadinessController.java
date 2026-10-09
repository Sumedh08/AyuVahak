package org.ayuvahak.capacity.adapter.api;

import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.capacity.application.CapacityEvidenceQueries;
import org.ayuvahak.capacity.application.CapacityReadiness;
import org.ayuvahak.capacity.domain.CapacityDimensionReadiness;
import org.ayuvahak.capacity.domain.CapacityObservation;
import org.ayuvahak.capacity.domain.CapacityReadinessEvaluation;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Protected API for deterministic Capacity readiness evidence evaluation. */
@RestController
@RequestMapping("/api/v1/capacity/readiness-evaluations")
public final class CapacityReadinessController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> REQUEST_FIELDS =
      Set.of("facilityId", "requiredDimensions", "evaluationTime");

  private final CapacityReadiness readiness;
  private final CapacityEvidenceQueries evidenceQueries;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;
  private final Validator validator;

  public CapacityReadinessController(
      CapacityReadiness readiness,
      CapacityEvidenceQueries evidenceQueries,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper,
      Validator validator) {
    this.readiness = readiness;
    this.evidenceQueries = evidenceQueries;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
    this.validator = validator;
  }

  @PostMapping
  public EvaluationRepresentation evaluate(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityScopeId,
      @RequestBody JsonNode body) {
    Request request = read(body);
    validate(request);
    ExternalReference facility = facilityReference(request.facilityId());
    ExecutionContext context =
        context(actor, scope, organizationId, facilityScopeId, request.facilityId());
    CapacityReadinessEvaluation result =
        readiness.evaluateOperationalReadiness(
            context,
            facility,
            request.requiredDimensions(),
            Optional.ofNullable(request.evaluationTime()));
    return EvaluationRepresentation.from(result);
  }

  private Request read(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "The Capacity readiness request must be a JSON object.");
    }
    if (body.properties().stream().anyMatch(field -> !REQUEST_FIELDS.contains(field.getKey()))) {
      throw invalid("The Capacity readiness request contains an unsupported field.");
    }
    try {
      return objectMapper.treeToValue(body, Request.class);
    } catch (JacksonException | IllegalArgumentException malformed) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "The Capacity readiness request is malformed.");
    }
  }

  private void validate(Request request) {
    if (!validator.validate(request).isEmpty()
        || request.requiredDimensions() != null
            && request.requiredDimensions().stream().distinct().count()
                != request.requiredDimensions().size()) {
      throw invalid("Request between 1 and 50 distinct valid Capacity dimensions.");
    }
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
      evidenceQueries.recordInvalidScope(actor, correlationId);
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Capacity readiness requires explicit matching Facility execution scope.");
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
      evidenceQueries.recordInvalidScope(actor, correlationId);
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "The requested Capacity Facility scope is not active or valid.");
    }
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

  public record Request(
      @NotBlank
          @Size(max = 36)
          @Pattern(
              regexp =
                  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
          String facilityId,
      @NotEmpty @Size(max = 50)
          List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9._-]{0,63}") String> requiredDimensions,
      Instant evaluationTime) {}

  public record EvaluationRepresentation(
      String facilityId,
      Instant evaluationTime,
      String outcome,
      List<DimensionRepresentation> dimensions) {

    public EvaluationRepresentation {
      dimensions = List.copyOf(dimensions);
    }

    static EvaluationRepresentation from(CapacityReadinessEvaluation result) {
      return new EvaluationRepresentation(
          result.facility().value(),
          result.evaluationTime(),
          result.outcome().name(),
          result.dimensions().stream().map(DimensionRepresentation::from).toList());
    }
  }

  public record DimensionRepresentation(
      String dimensionCode,
      String outcome,
      String freshness,
      Long dimensionVersion,
      Long capacityPolicyVersion,
      String projectionVersion,
      String valueKind,
      BigDecimal quantity,
      String state,
      String unit,
      boolean fallbackUsed,
      List<EvidenceRepresentation> evidence,
      List<String> reasonCodes) {

    public DimensionRepresentation {
      evidence = List.copyOf(evidence);
      reasonCodes = List.copyOf(reasonCodes);
    }

    static DimensionRepresentation from(CapacityDimensionReadiness result) {
      return new DimensionRepresentation(
          result.dimensionCode(),
          result.outcome().name(),
          result.freshness().name(),
          result.dimensionVersion().isPresent() ? result.dimensionVersion().getAsLong() : null,
          result.policyVersion().isPresent() ? result.policyVersion().getAsLong() : null,
          result.projectionVersion(),
          result.valueKind().map(Enum::name).orElse(null),
          result.quantity().orElse(null),
          result.state().orElse(null),
          result.unit().orElse(null),
          result.fallbackUsed(),
          result.evidence().stream().map(EvidenceRepresentation::from).toList(),
          result.reasonCodes());
    }
  }

  public record EvidenceRepresentation(
      String observationId,
      long dimensionVersion,
      long ingestionPolicyVersion,
      String sourceCode,
      ExternalReference sourceObservation,
      String reportingMethod,
      ActorReference reportedBy,
      Instant observedAt,
      Instant receivedAt,
      Instant sourceValidUntil,
      Instant validUntil,
      String reasonCode) {

    static EvidenceRepresentation from(CapacityObservation observation) {
      return new EvidenceRepresentation(
          observation.id().toString(),
          observation.dimensionVersion(),
          observation.policyVersion(),
          observation.sourceCode(),
          observation.sourceObservation(),
          observation.reportingMethod().name(),
          observation.reportedBy(),
          observation.observedAt(),
          observation.receivedAt(),
          observation.sourceValidUntil().orElse(null),
          observation.validUntil().orElse(null),
          observation.reasonCode());
    }
  }
}
