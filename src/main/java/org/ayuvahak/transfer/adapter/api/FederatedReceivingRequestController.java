package org.ayuvahak.transfer.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
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
import org.ayuvahak.transfer.application.FederatedReceivingCoordinationCommands;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest.Assessment;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest.AssessmentKind;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest.Outcome;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Local receiving-operator API for receiver-owned federated coordination cases. */
@RestController
@RequestMapping("/api/v1/federated-receiving-requests")
public final class FederatedReceivingRequestController {

  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> ASSESSMENT_FIELDS =
      Set.of("expectedVersion", "outcome", "reasonCode", "occurredAt");
  private static final Set<String> ACCEPTANCE_FIELDS = Set.of("expectedVersion", "occurredAt");

  private final FederatedReceivingCoordinationCommands commands;
  private final ExecutionContextFactory contextFactory;

  public FederatedReceivingRequestController(
      FederatedReceivingCoordinationCommands commands, ExecutionContextFactory contextFactory) {
    this.commands = java.util.Objects.requireNonNull(commands, "receiving commands");
    this.contextFactory = java.util.Objects.requireNonNull(contextFactory, "context factory");
  }

  @GetMapping("/{requestId}")
  public RequestRepresentation get(
      @PathVariable String requestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId) {
    return RequestRepresentation.from(
        commands.get(
            executionContext(actor, scope, organizationId, facilityId),
            parseId(requestId, "federated receiving request id")));
  }

  @PostMapping("/{requestId}/clinical-suitability-assessments")
  public RequestRepresentation clinicalAssessment(
      @PathVariable String requestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      HttpServletRequest request) {
    JsonNode body = FederatedReceivingOwnerJsonBody.read(request);
    AssessmentInput input = assessment(body);
    return RequestRepresentation.from(
        commands.recordAssessment(
            executionContext(actor, scope, organizationId, facilityId),
            parseId(requestId, "federated receiving request id"),
            AssessmentKind.CLINICAL,
            input.outcome(),
            input.reasonCode(),
            input.occurredAt(),
            input.expectedVersion()));
  }

  @PostMapping("/{requestId}/operational-readiness-assessments")
  public RequestRepresentation operationalAssessment(
      @PathVariable String requestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      HttpServletRequest request) {
    JsonNode body = FederatedReceivingOwnerJsonBody.read(request);
    AssessmentInput input = assessment(body);
    return RequestRepresentation.from(
        commands.recordAssessment(
            executionContext(actor, scope, organizationId, facilityId),
            parseId(requestId, "federated receiving request id"),
            AssessmentKind.OPERATIONAL,
            input.outcome(),
            input.reasonCode(),
            input.occurredAt(),
            input.expectedVersion()));
  }

  @PostMapping("/{requestId}/acceptance")
  public RequestRepresentation accept(
      @PathVariable String requestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      HttpServletRequest request) {
    JsonNode body = FederatedReceivingOwnerJsonBody.read(request);
    AcceptanceInput input = acceptance(body);
    return RequestRepresentation.from(
        commands.accept(
            executionContext(actor, scope, organizationId, facilityId),
            parseId(requestId, "federated receiving request id"),
            input.occurredAt(),
            input.expectedVersion()));
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
      if (!"FACILITY".equals(scope) || organizationId == null || facilityId == null) {
        throw invalidScope();
      }
      RequestedExecutionScope requested =
          RequestedExecutionScope.facility(
              directoryReference("Organization", organizationId),
              directoryReference("Facility", facilityId));
      return actor.kind() == ActorKind.HUMAN
          ? contextFactory.forInteractiveRequest(actor, requested, correlationId())
          : contextFactory.forIntegrationRequest(actor, requested, correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static AssessmentInput assessment(JsonNode body) {
    requireFields(body, ASSESSMENT_FIELDS);
    try {
      Outcome outcome = Outcome.valueOf(requiredText(body, "outcome"));
      String reasonCode = requiredText(body, "reasonCode");
      if (!reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
        throw invalid("The assessment reason code is invalid.");
      }
      return new AssessmentInput(
          outcome,
          reasonCode,
          parseInstant(body, "occurredAt"),
          positiveLong(body, "expectedVersion"));
    } catch (IllegalArgumentException invalid) {
      throw invalid("The federated receiving assessment is invalid.");
    }
  }

  private static AcceptanceInput acceptance(JsonNode body) {
    requireFields(body, ACCEPTANCE_FIELDS);
    return new AcceptanceInput(
        parseInstant(body, "occurredAt"), positiveLong(body, "expectedVersion"));
  }

  private static void requireFields(JsonNode body, Set<String> allowed) {
    if (body == null || !body.isObject()) {
      throw invalid("The request body must be a JSON object.");
    }
    java.util.Set<String> fields = new java.util.HashSet<>();
    body.properties()
        .forEach(
            property -> {
              if (!allowed.contains(property.getKey())) {
                throw invalid("The request contains an unsupported field.");
              }
              fields.add(property.getKey());
            });
    if (!fields.equals(allowed)) {
      throw invalid("The request is missing a required field.");
    }
  }

  private static String requiredText(JsonNode body, String name) {
    JsonNode value = body.get(name);
    if (value == null || !value.isTextual() || value.asString().isBlank()) {
      throw invalid("A required text value is missing.");
    }
    return value.asString();
  }

  private static Instant parseInstant(JsonNode body, String name) {
    try {
      return Instant.parse(requiredText(body, name));
    } catch (RuntimeException malformed) {
      throw invalid("A request timestamp is invalid.");
    }
  }

  private static long positiveLong(JsonNode body, String name) {
    JsonNode value = body.get(name);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid("A positive version is required.");
    }
    long result = value.longValue();
    if (result < 1) {
      throw invalid("A positive version is required.");
    }
    return result;
  }

  private static InternalId parseId(String value, String label) {
    try {
      return InternalId.parse(value);
    } catch (IllegalArgumentException malformed) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "The " + label + " is invalid.");
    }
  }

  private static ExternalReference directoryReference(String type, String value) {
    return new ExternalReference(
        new IssuerId("urn:ayuvahak:directory"), type, parseId(value, type + " id").toString());
  }

  private static CorrelationId correlationId() {
    String value = MDC.get("correlationId");
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "A current local Facility scope is required for this receiving operation.");
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.MALFORMED_REQUEST, detail);
  }

  private record AssessmentInput(
      Outcome outcome, String reasonCode, Instant occurredAt, long expectedVersion) {}

  private record AcceptanceInput(Instant occurredAt, long expectedVersion) {}

  public record RequestRepresentation(
      String id,
      String originDeploymentId,
      String operationId,
      String originJourneyReference,
      String originFacilityReference,
      String destinationOrganizationId,
      String destinationFacilityId,
      String status,
      String clinicalOutcome,
      String clinicalReasonCode,
      String operationalOutcome,
      String operationalReasonCode,
      String capacityStatus,
      String capacityReservationId,
      long version,
      String updatedAt) {

    static RequestRepresentation from(FederatedReceivingRequest request) {
      Optional<Assessment> clinical = request.clinicalAssessment();
      Optional<Assessment> operational = request.operationalAssessment();
      return new RequestRepresentation(
          request.id().toString(),
          request.originDeployment().value(),
          request.requestOperationId().toString(),
          request.originJourneyId().toString(),
          request.originFacilityId(),
          request.destinationOrganization().value(),
          request.destinationFacility().value(),
          request.status().name(),
          clinical.map(a -> a.outcome().name()).orElse(null),
          clinical.map(Assessment::reasonCode).orElse(null),
          operational.map(a -> a.outcome().name()).orElse(null),
          operational.map(Assessment::reasonCode).orElse(null),
          request.reservationStatus().name(),
          request.capacityReservationId().map(InternalId::toString).orElse(null),
          request.version(),
          request.updatedAt().toString());
    }
  }
}
