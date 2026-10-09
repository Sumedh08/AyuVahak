package org.ayuvahak.capacity.adapter.api;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.ayuvahak.capacity.application.CapacityPolicyAdministration;
import org.ayuvahak.capacity.application.CapacityVersionCommandResult;
import org.ayuvahak.capacity.domain.CapacityDimensionDefinition;
import org.ayuvahak.capacity.domain.CapacityPolicyDefinition;
import org.ayuvahak.capacity.domain.CapacitySourcePriority;
import org.ayuvahak.capacity.domain.CapacityValueKind;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeDeniedException;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HTTP management boundary for typed, versioned Capacity dimensions and policies. */
@RestController
@RequestMapping("/api/v1/capacity/policies")
public final class CapacityPolicyController {

  public static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final Set<String> DIMENSION_FIELDS =
      Set.of(
          "code",
          "expectedVersion",
          "displayName",
          "meaning",
          "valueKind",
          "unit",
          "minimumValue",
          "maximumValue",
          "allowedStates",
          "usedForReadiness",
          "reservable",
          "reasonCode");
  private static final Set<String> POLICY_FIELDS =
      Set.of(
          "expectedVersion",
          "dimensionVersion",
          "sources",
          "maximumObservationAgeSeconds",
          "defaultValiditySeconds",
          "futureSkewToleranceSeconds",
          "minimumPassingQuantity",
          "passingStates",
          "reasonCode");
  private static final Set<String> SOURCE_FIELDS =
      Set.of(
          "sourceCode",
          "priority",
          "fallbackWhenHigherPriorityAbsent",
          "fallbackWhenHigherPriorityStale");

  private final CapacityPolicyAdministration administration;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;
  private final Validator validator;

  public CapacityPolicyController(
      CapacityPolicyAdministration administration,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper,
      Validator validator) {
    this.administration = administration;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
    this.validator = validator;
  }

  @PostMapping("/dimensions")
  public ResponseEntity<VersionRepresentation> defineDimension(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    DimensionRequest request = read(body, DIMENSION_FIELDS, DimensionRequest.class);
    validate(request);
    CapacityDimensionDefinition definition;
    try {
      definition =
          new CapacityDimensionDefinition(
              request.code(),
              request.displayName(),
              request.meaning(),
              CapacityValueKind.valueOf(request.valueKind()),
              request.unit(),
              request.minimumValue(),
              request.maximumValue(),
              new TreeSet<>(request.allowedStates()),
              request.usedForReadiness(),
              request.reservable(),
              request.reasonCode());
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw invalid("The dimension definition is not a valid typed Capacity dimension.");
    }
    ExecutionContext context = context(actor, scope);
    CapacityVersionCommandResult result =
        administration.defineDimension(
            context, definition, request.expectedVersion(), idempotencyKey, fingerprint(request));
    return response(result);
  }

  @PostMapping("/dimensions/{dimensionCode}/policy")
  public ResponseEntity<VersionRepresentation> configurePolicy(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String dimensionCode,
      @RequestBody JsonNode body) {
    PolicyRequest request = read(body, POLICY_FIELDS, PolicyRequest.class);
    validate(request);
    CapacityPolicyDefinition definition;
    try {
      List<CapacitySourcePriority> sources =
          request.sources().stream()
              .map(
                  source ->
                      new CapacitySourcePriority(
                          source.sourceCode(),
                          source.priority(),
                          source.fallbackWhenHigherPriorityAbsent(),
                          source.fallbackWhenHigherPriorityStale()))
              .toList();
      definition =
          new CapacityPolicyDefinition(
              dimensionCode,
              request.dimensionVersion(),
              sources,
              request.maximumObservationAgeSeconds(),
              request.defaultValiditySeconds(),
              request.futureSkewToleranceSeconds(),
              request.minimumPassingQuantity(),
              new TreeSet<>(request.passingStates()),
              request.reasonCode());
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw invalid("The source, freshness, or readiness policy is invalid.");
    }
    ExecutionContext context = context(actor, scope);
    CapacityVersionCommandResult result =
        administration.configurePolicy(
            context,
            dimensionCode,
            request.expectedVersion(),
            definition,
            idempotencyKey,
            fingerprint(request));
    return response(result);
  }

  private ExecutionContext context(ActorReference actor, String scope) {
    CorrelationId correlationId = correlationId();
    if (!"PLATFORM".equals(scope)) {
      administration.recordInvalidScope(actor, correlationId);
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Capacity policy administration requires platform execution scope.");
    }
    try {
      if (actor.kind() == ActorKind.HUMAN) {
        return contextFactory.forInteractiveRequest(
            actor, RequestedExecutionScope.platform(), correlationId);
      }
      return contextFactory.forIntegrationRequest(
          actor, RequestedExecutionScope.platform(), correlationId);
    } catch (ExecutionScopeDeniedException invalid) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "The requested Capacity execution scope is invalid.");
    }
  }

  private <T> T read(JsonNode body, Set<String> allowedFields, Class<T> type) {
    if (body == null || !body.isObject()) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "The Capacity command body must be a JSON object.");
    }
    if (body.properties().stream().anyMatch(field -> !allowedFields.contains(field.getKey()))) {
      throw invalid("The Capacity command contains an unsupported field.");
    }
    try {
      return objectMapper.treeToValue(body, type);
    } catch (JacksonException | IllegalArgumentException malformed) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "The Capacity command body is malformed.");
    }
  }

  private void validate(Object request) {
    if (!validator.validate(request).isEmpty()) {
      throw invalid("One or more Capacity policy fields are missing or invalid.");
    }
  }

  private RequestFingerprint fingerprint(Object request) {
    return RequestFingerprint.from(objectMapper.valueToTree(request));
  }

  private static ResponseEntity<VersionRepresentation> response(
      CapacityVersionCommandResult result) {
    HttpHeaders headers = new HttpHeaders();
    if (result.replayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        new VersionRepresentation(result.id().toString(), result.code(), result.version()),
        headers,
        org.springframework.http.HttpStatusCode.valueOf(result.responseStatus()));
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.CAPACITY_VALUE_INVALID, detail);
  }

  public record DimensionRequest(
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9._-]{0,63}") String code,
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank String displayName,
      @NotBlank String meaning,
      @NotBlank @Pattern(regexp = "QUANTITY|STATE") String valueKind,
      String unit,
      BigDecimal minimumValue,
      BigDecimal maximumValue,
      @NotNull List<@Pattern(regexp = "[A-Z][A-Z0-9._-]{0,63}") String> allowedStates,
      @NotNull Boolean usedForReadiness,
      @NotNull Boolean reservable,
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String reasonCode) {}

  public record PolicyRequest(
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotNull @Positive Long dimensionVersion,
      @NotEmpty List<@NotNull @Valid SourcePriorityRequest> sources,
      @NotNull @Positive Long maximumObservationAgeSeconds,
      @Positive Long defaultValiditySeconds,
      @NotNull @PositiveOrZero Long futureSkewToleranceSeconds,
      BigDecimal minimumPassingQuantity,
      @NotNull List<@Pattern(regexp = "[A-Z][A-Z0-9._-]{0,63}") String> passingStates,
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String reasonCode) {}

  public record SourcePriorityRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9._:-]{0,127}") String sourceCode,
      @NotNull @PositiveOrZero Integer priority,
      @NotNull Boolean fallbackWhenHigherPriorityAbsent,
      @NotNull Boolean fallbackWhenHigherPriorityStale) {}

  public record VersionRepresentation(String id, String code, long version) {}
}
