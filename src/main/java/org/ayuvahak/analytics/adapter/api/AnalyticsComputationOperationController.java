package org.ayuvahak.analytics.adapter.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.ayuvahak.analytics.application.AnalyticsAggregateComputationService;
import org.ayuvahak.analytics.application.AnalyticsComputationException;
import org.ayuvahak.analytics.application.AnalyticsComputationOperation;
import org.ayuvahak.analytics.application.AnalyticsComputationRequest;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeDeniedException;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Durable service-only command surface for one bounded Analytics aggregate computation. */
@RestController
@RequestMapping("/api/v1/analytics/computation-operations")
public final class AnalyticsComputationOperationController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "computationRequestId",
          "indicatorKey",
          "indicatorVersion",
          "startInclusive",
          "endExclusive",
          "referralSourceCutoff");

  private final AnalyticsAggregateComputationService computations;
  private final ExecutionContextFactory contexts;

  public AnalyticsComputationOperationController(
      AnalyticsAggregateComputationService computations, ExecutionContextFactory contexts) {
    this.computations = computations;
    this.contexts = contexts;
  }

  @PostMapping
  public ResponseEntity<OperationAcceptedView> enqueue(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestBody JsonNode body) {
    if (actor == null || actor.kind() != ActorKind.SERVICE) {
      throw problem(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified Analytics service identity is required.");
    }
    var request = parse(body);
    final org.ayuvahak.platform.executioncontext.ExecutionContext context;
    try {
      context =
          contexts.forIntegrationRequest(
              actor, requestedScope(scope, organizationId, facilityId), correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Analytics computation requires a validated Directory organization or facility scope.");
    }
    try {
      AnalyticsComputationOperation operation = computations.enqueue(context, request);
      return ResponseEntity.accepted()
          .body(new OperationAcceptedView(operation.requestId().toString(), operation.state().name()));
    } catch (AnalyticsComputationException failure) {
      throw mapFailure(failure);
    } catch (IllegalArgumentException invalid) {
      throw invalidRequest();
    }
  }

  private static AnalyticsComputationRequest parse(JsonNode body) {
    try {
      requireOnlyFields(body);
      return new AnalyticsComputationRequest(
          InternalId.parse(text(body, "computationRequestId")),
          text(body, "indicatorKey"),
          integer(body, "indicatorVersion"),
          LocalDate.parse(text(body, "startInclusive")),
          LocalDate.parse(text(body, "endExclusive")),
          Instant.parse(text(body, "referralSourceCutoff")));
    } catch (PlatformProblemException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.VALIDATION_FAILED,
          "The Analytics computation request is invalid.");
    }
  }

  private static RequestedExecutionScope requestedScope(
      String scope, String organizationId, String facilityId) {
    try {
      if ("ORGANIZATION".equals(scope) && organizationId != null && facilityId == null) {
        return RequestedExecutionScope.organization(reference("Organization", organizationId));
      }
      if ("FACILITY".equals(scope) && organizationId != null && facilityId != null) {
        return RequestedExecutionScope.facility(
            reference("Organization", organizationId), reference("Facility", facilityId));
      }
    } catch (IllegalArgumentException invalid) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Analytics computation requires a validated Directory organization or facility scope.");
    }
    throw problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Analytics computation requires a validated Directory organization or facility scope.");
  }

  private static ExternalReference reference(String resourceType, String value) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), resourceType, InternalId.parse(value).toString());
  }

  private static void requireOnlyFields(JsonNode body) {
    if (body == null || !body.isObject()) throw invalidRequest();
    body.properties().forEach(
        field -> {
          if (!REQUEST_FIELDS.contains(field.getKey())) throw invalidRequest();
        });
    if (body.size() != REQUEST_FIELDS.size()) throw invalidRequest();
  }

  private static String text(JsonNode body, String name) {
    JsonNode value = body.get(name);
    if (value == null || !value.isString() || value.stringValue().isBlank()) throw invalidRequest();
    return value.stringValue();
  }

  private static int integer(JsonNode body, String name) {
    JsonNode value = body.get(name);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalidRequest();
    return value.intValue();
  }

  private static PlatformProblemException mapFailure(AnalyticsComputationException failure) {
    if (failure.reason() == AnalyticsComputationException.Reason.NOT_AUTHORIZED) {
      return problem(HttpStatus.FORBIDDEN, PlatformErrorCode.AUTHORIZATION_DENIED, "Analytics computation is not authorized.");
    }
    if (failure.reason() == AnalyticsComputationException.Reason.DEFINITION_NOT_FOUND) {
      return problem(HttpStatus.NOT_FOUND, PlatformErrorCode.NOT_FOUND, "The approved indicator version is unavailable.");
    }
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.VALIDATION_FAILED, "The Analytics computation request could not be accepted.");
  }

  private static PlatformProblemException invalidRequest() {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.VALIDATION_FAILED, "The Analytics computation request contains unsupported or invalid fields.");
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  public record OperationAcceptedView(String operationId, String state) {}
}
