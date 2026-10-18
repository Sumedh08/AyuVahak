package org.ayuvahak.transfer.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
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
import org.ayuvahak.transfer.application.FederatedOriginReceivingAttemptResult;
import org.ayuvahak.transfer.application.FederatedReceivingOriginCommand;
import org.ayuvahak.transfer.application.FederatedReceivingOriginCommands;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Origin Transfer boundary for one trusted, owner-authorized remote receiving request. */
@RestController
@RequestMapping("/api/v1/transfer-journeys/{journeyId}/federated-receiving-requests")
public final class FederatedReceivingOriginController {

  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final Set<String> FIELDS =
      Set.of(
          "expectedJourneyVersion",
          "referralRevision",
          "recommendationId",
          "destinationOrganization",
          "destinationFacility",
          "capacityDimensionCode",
          "capacityUnit",
          "capacityQuantity",
          "capacityIntervalStart",
          "capacityIntervalEnd");
  private static final Set<String> REFERENCE_FIELDS = Set.of("issuer", "resourceType", "value");
  private static final Set<String> REMOTE_FACILITY_FIELDS =
      Set.of("deploymentId", "context", "resourceId");

  private final FederatedReceivingOriginCommands commands;
  private final ExecutionContextFactory contextFactory;

  public FederatedReceivingOriginController(
      FederatedReceivingOriginCommands commands, ExecutionContextFactory contextFactory) {
    this.commands = java.util.Objects.requireNonNull(commands, "origin receiving commands");
    this.contextFactory = java.util.Objects.requireNonNull(contextFactory, "context factory");
  }

  @PostMapping
  public ResponseEntity<AttemptRepresentation> request(
      @PathVariable String journeyId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    JsonNode body = FederatedReceivingOwnerJsonBody.read(request);
    FederatedReceivingOriginCommand command = command(body);
    FederatedOriginReceivingAttemptResult result =
        commands.request(
            executionContext(actor, scope, organizationId, facilityId),
            parseId(journeyId, "TransferJourney id"),
            command,
            idempotencyKey);
    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(URI.create("/api/v1/transfer-journeys/" + journeyId));
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.replayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        AttemptRepresentation.from(result.attempt()), headers, HttpStatus.CREATED);
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

  private static FederatedReceivingOriginCommand command(JsonNode body) {
    requireFields(body, FIELDS);
    try {
      return new FederatedReceivingOriginCommand(
          positiveLong(body, "expectedJourneyVersion"),
          positiveLong(body, "referralRevision"),
          parseId(requiredText(body, "recommendationId"), "recommendation id"),
          parseReference(body.get("destinationOrganization"), "Organization"),
          parseRemoteFacility(body.get("destinationFacility")),
          requiredText(body, "capacityDimensionCode"),
          requiredText(body, "capacityUnit"),
          positiveQuantity(body.get("capacityQuantity")),
          parseInstant(body, "capacityIntervalStart"),
          parseInstant(body, "capacityIntervalEnd"));
    } catch (IllegalArgumentException invalid) {
      throw malformed("The federated receiving request is invalid.");
    }
  }

  private static ExternalReference parseReference(JsonNode body, String expectedType) {
    requireFields(body, REFERENCE_FIELDS);
    String resourceType = requiredText(body, "resourceType");
    if (!expectedType.equals(resourceType)) {
      throw malformed("The destination Organization reference is invalid.");
    }
    return new ExternalReference(
        new IssuerId(requiredText(body, "issuer")),
        resourceType,
        parseId(requiredText(body, "value"), "remote resource id").toString());
  }

  private static RemoteFacilityReference parseRemoteFacility(JsonNode body) {
    requireFields(body, REMOTE_FACILITY_FIELDS);
    String context = requiredText(body, "context");
    if (!"directory".equals(context)) {
      throw malformed("The destination Facility context is invalid.");
    }
    return new RemoteFacilityReference(
        new org.ayuvahak.platform.federation.domain.DeploymentId(
            requiredText(body, "deploymentId")),
        context,
        parseId(requiredText(body, "resourceId"), "remote Facility id"));
  }

  private static BigDecimal positiveQuantity(JsonNode value) {
    if (value == null || !value.isNumber()) {
      throw malformed("A positive Capacity quantity is required.");
    }
    BigDecimal quantity = value.decimalValue();
    if (quantity.signum() <= 0 || quantity.precision() > 12 || Math.max(0, quantity.scale()) > 6) {
      throw malformed("The Capacity quantity is outside its supported bound.");
    }
    return quantity;
  }

  private static Instant parseInstant(JsonNode body, String field) {
    try {
      return Instant.parse(requiredText(body, field));
    } catch (RuntimeException invalid) {
      throw malformed("A Capacity interval timestamp is invalid.");
    }
  }

  private static void requireFields(JsonNode body, Set<String> fields) {
    if (body == null || !body.isObject()) {
      throw malformed("The request body must be a JSON object.");
    }
    java.util.Set<String> actual = new java.util.HashSet<>();
    body.properties()
        .forEach(
            property -> {
              if (!fields.contains(property.getKey())) {
                throw malformed("The request contains an unsupported field.");
              }
              actual.add(property.getKey());
            });
    if (!actual.equals(fields)) {
      throw malformed("The request is missing a required field.");
    }
  }

  private static String requiredText(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.isTextual() || value.asString().isBlank()) {
      throw malformed("A required text value is missing.");
    }
    return value.asString();
  }

  private static long positiveLong(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw malformed("A positive version is required.");
    }
    long result = value.longValue();
    if (result < 1) {
      throw malformed("A positive version is required.");
    }
    return result;
  }

  private static InternalId parseId(String value, String label) {
    try {
      return InternalId.parse(value);
    } catch (IllegalArgumentException invalid) {
      throw malformed("The " + label + " is invalid.");
    }
  }

  private static ExternalReference directoryReference(String type, String value) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), type, parseId(value, type + " id").toString());
  }

  private static CorrelationId correlationId() {
    String value = MDC.get("correlationId");
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "A current local Transfer scope is required for this operation.");
  }

  private static PlatformProblemException malformed(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.MALFORMED_REQUEST, detail);
  }

  public record AttemptRepresentation(
      String attemptId,
      int attemptNumber,
      String originDeploymentId,
      String peerDeploymentId,
      String operationId,
      String state,
      String remoteOutcome,
      String remoteReservationStatus,
      String receivingRequestId,
      String capacityReservationId,
      RemoteAssessmentRepresentation clinicalAssessment,
      RemoteAssessmentRepresentation operationalAssessment,
      String remoteAcceptedAt,
      String remoteSourceUpdatedAt,
      long remoteVersion,
      long version,
      String createdAt,
      String updatedAt) {

    static AttemptRepresentation from(FederatedOriginReceivingAttempt attempt) {
      return new AttemptRepresentation(
          attempt.id().toString(),
          attempt.attemptNumber(),
          attempt.originDeployment().value(),
          attempt.peerDeployment().value(),
          attempt.operationId().toString(),
          attempt.state().name(),
          attempt.remoteOutcome().map(Enum::name).orElse(null),
          attempt.remoteReservationStatus().map(Enum::name).orElse(null),
          attempt.remoteReceivingRequestId().map(InternalId::toString).orElse(null),
          attempt.remoteCapacityReservationId().map(InternalId::toString).orElse(null),
          attempt.remoteClinicalAssessment().map(RemoteAssessmentRepresentation::from).orElse(null),
          attempt
              .remoteOperationalAssessment()
              .map(RemoteAssessmentRepresentation::from)
              .orElse(null),
          attempt.remoteAcceptedAt().map(Instant::toString).orElse(null),
          attempt.remoteSourceUpdatedAt().map(Instant::toString).orElse(null),
          attempt.remoteVersion(),
          attempt.version(),
          attempt.createdAt().toString(),
          attempt.updatedAt().toString());
    }
  }

  public record RemoteAssessmentRepresentation(
      String outcome, String reasonCode, String occurredAt, String recordedAt) {
    private static RemoteAssessmentRepresentation from(
        FederatedOriginReceivingAttempt.RemoteAssessmentEvidence assessment) {
      return new RemoteAssessmentRepresentation(
          assessment.outcome().name(),
          assessment.reasonCode(),
          assessment.occurredAt().toString(),
          assessment.recordedAt().toString());
    }
  }
}
