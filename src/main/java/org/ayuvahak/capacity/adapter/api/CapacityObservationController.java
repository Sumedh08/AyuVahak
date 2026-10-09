package org.ayuvahak.capacity.adapter.api;

import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.capacity.application.CapacityObservationCommandResult;
import org.ayuvahak.capacity.application.CapacityObservationCommands;
import org.ayuvahak.capacity.application.CapacityObservationInput;
import org.ayuvahak.capacity.domain.CapacityObservationKind;
import org.ayuvahak.capacity.domain.CapacityReportingMethod;
import org.ayuvahak.capacity.domain.CapacityValueKind;
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
import tools.jackson.databind.node.ObjectNode;

/** Authenticated HTTP boundary for attributed Capacity observations and append-only corrections. */
@RestController
@RequestMapping("/api/v1/capacity/observations")
public final class CapacityObservationController {

  public static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  public static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  public static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final java.util.Set<String> REQUEST_FIELDS =
      java.util.Set.of(
          "dimensionCode",
          "sourceCode",
          "sourceIssuer",
          "sourceObservationId",
          "observationKind",
          "valueKind",
          "quantity",
          "state",
          "unit",
          "observedAt",
          "validUntil",
          "reportingMethod",
          "reasonCode");

  private final CapacityObservationCommands commands;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;
  private final Validator validator;

  public CapacityObservationController(
      CapacityObservationCommands commands,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper,
      Validator validator) {
    this.commands = commands;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
    this.validator = validator;
  }

  @PostMapping
  public ResponseEntity<ObservationRepresentation> submit(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    ObservationRequest request = read(body);
    validate(request);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    CapacityObservationInput input = input(request, Optional.empty());
    CapacityObservationCommandResult result =
        commands.submit(context, input, idempotencyKey, fingerprint(request, Optional.empty()));
    return response(result);
  }

  @PostMapping("/{observationId}/corrections")
  public ResponseEntity<ObservationRepresentation> correct(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String observationId,
      @RequestBody JsonNode body) {
    ObservationRequest request = read(body);
    validate(request);
    InternalId supersededId = parseObservationId(observationId);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    CapacityObservationInput input = input(request, Optional.of(supersededId));
    CapacityObservationCommandResult result =
        commands.correct(
            context,
            supersededId,
            input,
            idempotencyKey,
            fingerprint(request, Optional.of(supersededId)));
    return response(result);
  }

  private ObservationRequest read(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw malformed();
    }
    if (body.properties().stream().anyMatch(field -> !REQUEST_FIELDS.contains(field.getKey()))) {
      throw invalid("The observation contains an unsupported field.");
    }
    try {
      return objectMapper.treeToValue(body, ObservationRequest.class);
    } catch (JacksonException | IllegalArgumentException malformed) {
      throw malformed();
    }
  }

  private void validate(ObservationRequest request) {
    if (!validator.validate(request).isEmpty()) {
      throw invalid("One or more observation fields are missing or invalid.");
    }
  }

  private CapacityObservationInput input(
      ObservationRequest request, Optional<InternalId> supersedesObservationId) {
    try {
      ExternalReference source =
          new ExternalReference(
              new IssuerId(request.sourceIssuer()),
              "CapacityObservation",
              request.sourceObservationId());
      return new CapacityObservationInput(
          request.dimensionCode(),
          request.sourceCode(),
          source,
          CapacityObservationKind.valueOf(request.observationKind()),
          Optional.ofNullable(request.valueKind()).map(CapacityValueKind::valueOf),
          Optional.ofNullable(request.quantity()),
          Optional.ofNullable(request.state()),
          Optional.ofNullable(request.unit()),
          request.observedAt(),
          Optional.ofNullable(request.validUntil()),
          CapacityReportingMethod.valueOf(request.reportingMethod()),
          request.reasonCode(),
          supersedesObservationId);
    } catch (IllegalArgumentException | NullPointerException malformed) {
      throw invalid("The typed observation fields are not valid.");
    }
  }

  private ExecutionContext context(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    CorrelationId correlationId = correlationId();
    if (!"FACILITY".equals(scope) || organizationId == null || facilityId == null) {
      commands.recordInvalidScope(actor, correlationId);
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Capacity observation commands require explicit Facility execution scope.");
    }
    try {
      RequestedExecutionScope requestedScope =
          RequestedExecutionScope.facility(
              directoryReference("Organization", organizationId),
              directoryReference("Facility", facilityId));
      if (actor.kind() == ActorKind.HUMAN) {
        return contextFactory.forInteractiveRequest(actor, requestedScope, correlationId);
      }
      return contextFactory.forIntegrationRequest(actor, requestedScope, correlationId);
    } catch (DirectoryScopeLookupUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.CAPACITY_UNAVAILABLE,
          "Directory could not validate the Facility execution scope.");
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      commands.recordInvalidScope(actor, correlationId);
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "The requested Capacity Facility scope is not active or valid.");
    }
  }

  private RequestFingerprint fingerprint(
      ObservationRequest request, Optional<InternalId> supersedesObservationId) {
    ObjectNode normalized = objectMapper.valueToTree(request);
    normalized.put(
        "supersedesObservationId", supersedesObservationId.map(InternalId::toString).orElse(null));
    return RequestFingerprint.from(normalized);
  }

  private static ResponseEntity<ObservationRepresentation> response(
      CapacityObservationCommandResult result) {
    HttpHeaders headers = new HttpHeaders();
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    if (result.sourceReplayed()) {
      headers.set("Source-Observation-Replayed", "true");
    }
    return new ResponseEntity<>(
        new ObservationRepresentation(
            result.id().toString(),
            result.dimensionCode(),
            result.kind().name(),
            result.receivedAt(),
            result.validUntil().orElse(null),
            result.futureSkewed()),
        headers,
        org.springframework.http.HttpStatusCode.valueOf(result.responseStatus()));
  }

  private static ExternalReference directoryReference(String type, String id) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(id).toString());
  }

  private static InternalId parseObservationId(String serialized) {
    try {
      return InternalId.parse(serialized);
    } catch (RuntimeException malformed) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.NOT_FOUND,
          "The Capacity observation does not exist.");
    }
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException malformed() {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.MALFORMED_REQUEST,
        "The observation request body is malformed.");
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.CAPACITY_VALUE_INVALID, detail);
  }

  public record ObservationRequest(
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9._-]{0,63}") String dimensionCode,
      @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z][A-Za-z0-9._:-]{0,127}")
          String sourceCode,
      @NotBlank @Size(max = 512) String sourceIssuer,
      @NotBlank @Size(max = 256) String sourceObservationId,
      @NotBlank @Pattern(regexp = "VALUE|RETRACTION") String observationKind,
      @Pattern(regexp = "QUANTITY|STATE") String valueKind,
      BigDecimal quantity,
      @Pattern(regexp = "[A-Z][A-Z0-9._-]{0,63}") String state,
      @Size(max = 32) @Pattern(regexp = "[A-Za-z][A-Za-z0-9._-]{0,31}") String unit,
      @NotNull Instant observedAt,
      Instant validUntil,
      @NotBlank @Pattern(regexp = "MANUAL_ENTRY|MANUAL_CORRECTION|AUTOMATED")
          String reportingMethod,
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String reasonCode) {}

  public record ObservationRepresentation(
      String id,
      String dimensionCode,
      String kind,
      Instant receivedAt,
      Instant validUntil,
      boolean futureSkewed) {}
}
