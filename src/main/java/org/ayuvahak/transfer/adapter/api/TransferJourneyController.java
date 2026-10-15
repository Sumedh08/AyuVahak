package org.ayuvahak.transfer.adapter.api;

import java.net.URI;
import java.time.Instant;
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
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.application.TransferJourneyClosureCommandResult;
import org.ayuvahak.transfer.application.TransferJourneyClosureCommands;
import org.ayuvahak.transfer.application.TransferJourneyCommandResult;
import org.ayuvahak.transfer.application.TransferJourneyCommands;
import org.ayuvahak.transfer.application.TransferNextRequestCommands;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** HTTP boundary for creating a TransferJourney and its first request attempt. */
@RestController
@RequestMapping("/api/v1/transfer-journeys")
public final class TransferJourneyController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "referralId",
          "referralRevision",
          "recommendationId",
          "candidateFacilityId",
          "transportRequired");
  private static final Set<String> NEXT_REQUEST_FIELDS =
      Set.of("expectedVersion", "referralRevision", "recommendationId", "candidateFacilityId");
  private static final Set<String> CLOSURE_FIELDS =
      Set.of("expectedVersion", "reasonCode", "occurredAt");

  private final TransferJourneyCommands commands;
  private final TransferNextRequestCommands nextRequestCommands;
  private final TransferJourneyClosureCommands closureCommands;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;

  public TransferJourneyController(
      TransferJourneyCommands commands,
      TransferNextRequestCommands nextRequestCommands,
      TransferJourneyClosureCommands closureCommands,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper) {
    this.commands = commands;
    this.nextRequestCommands = nextRequestCommands;
    this.closureCommands = closureCommands;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/{journeyId}/closure")
  public ResponseEntity<TransferJourneyClosureRepresentation> closeUnfulfilledJourney(
      @PathVariable String journeyId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    ClosureContent content = closureContent(body);
    ExecutionContext context = executionContext(actor, scope, organizationId, facilityId);
    InternalId id = parseId(journeyId, "TransferJourney id");
    TransferJourneyClosureCommandResult result =
        closureCommands.closeUnfulfilled(
            context,
            id,
            content.reasonCode(),
            content.occurredAt(),
            content.expectedVersion(),
            idempotencyKey,
            RequestFingerprint.from(content.normalized()));
    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(URI.create("/api/v1/transfer-journeys/" + id));
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        TransferJourneyClosureRepresentation.from(result), headers, HttpStatus.CREATED);
  }

  @PostMapping("/{journeyId}/request-attempts")
  public ResponseEntity<TransferJourneyRepresentation> createNextRequest(
      @PathVariable String journeyId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    NextRequestContent content = nextRequestContent(body);
    ExecutionContext context = executionContext(actor, scope, organizationId, facilityId);
    TransferJourneyCommandResult result =
        nextRequestCommands.createNextRequest(
            context,
            parseId(journeyId, "TransferJourney id"),
            content.expectedVersion(),
            content.referralRevision(),
            content.recommendationId(),
            content.candidateFacility(),
            idempotencyKey,
            RequestFingerprint.from(content.normalized()));
    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(URI.create("/api/v1/transfer-journeys/" + result.journey().id()));
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        TransferJourneyRepresentation.from(result), headers, HttpStatus.CREATED);
  }

  @PostMapping
  public ResponseEntity<TransferJourneyRepresentation> createFirstRequest(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    RequestContent content = requestContent(body);
    ExecutionContext context = executionContext(actor, scope, organizationId, facilityId);
    TransferJourneyCommandResult result =
        commands.createFirstRequest(
            context,
            content.referralId(),
            content.referralRevision(),
            content.recommendationId(),
            content.candidateFacility(),
            content.transportRequired(),
            idempotencyKey,
            RequestFingerprint.from(content.normalized()));

    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(URI.create("/api/v1/transfer-journeys/" + result.journey().id()));
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        TransferJourneyRepresentation.from(result), headers, HttpStatus.CREATED);
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
                directoryReference("Organization", InternalId.parse(organizationId)));
      } else if ("FACILITY".equals(scope) && organizationId != null && facilityId != null) {
        requested =
            RequestedExecutionScope.facility(
                directoryReference("Organization", InternalId.parse(organizationId)),
                directoryReference("Facility", InternalId.parse(facilityId)));
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

  private RequestContent requestContent(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "The Transfer request body must be a JSON object.");
    }
    body.properties()
        .forEach(
            field -> {
              if (!REQUEST_FIELDS.contains(field.getKey())) {
                throw invalid("The Transfer request contains an unsupported field.");
              }
            });
    JsonNode revision = body.get("referralRevision");
    if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()) {
      throw invalid("A positive Referral revision is required.");
    }
    InternalId referralId = parseId(body.get("referralId"), "Referral id");
    InternalId recommendationId = parseId(body.get("recommendationId"), "recommendation id");
    InternalId destinationId = parseId(body.get("candidateFacilityId"), "candidate Facility id");
    JsonNode transportRequiredNode = body.get("transportRequired");
    if (transportRequiredNode != null && !transportRequiredNode.isBoolean()) {
      throw invalid("transportRequired must be a boolean when supplied.");
    }
    boolean transportRequired =
        transportRequiredNode != null && transportRequiredNode.booleanValue();
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("referralId", referralId.toString());
    normalized.put("referralRevision", revision.longValue());
    normalized.put("recommendationId", recommendationId.toString());
    normalized.put("candidateFacilityId", destinationId.toString());
    normalized.put("transportRequired", transportRequired);
    return new RequestContent(
        referralId,
        revision.longValue(),
        recommendationId,
        directoryReference("Facility", destinationId),
        transportRequired,
        normalized);
  }

  private NextRequestContent nextRequestContent(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "The next Transfer request body must be a JSON object.");
    }
    body.properties()
        .forEach(
            field -> {
              if (!NEXT_REQUEST_FIELDS.contains(field.getKey())) {
                throw invalid("The next Transfer request contains an unsupported field.");
              }
            });
    JsonNode expectedVersion = body.get("expectedVersion");
    JsonNode revision = body.get("referralRevision");
    if (expectedVersion == null
        || !expectedVersion.isIntegralNumber()
        || !expectedVersion.canConvertToLong()
        || expectedVersion.longValue() < 1) {
      throw invalid("A positive expected Journey version is required.");
    }
    if (revision == null
        || !revision.isIntegralNumber()
        || !revision.canConvertToLong()
        || revision.longValue() < 1) {
      throw invalid("A positive Referral revision is required.");
    }
    InternalId recommendationId = parseId(body.get("recommendationId"), "recommendation id");
    InternalId destinationId = parseId(body.get("candidateFacilityId"), "candidate Facility id");
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", expectedVersion.longValue());
    normalized.put("referralRevision", revision.longValue());
    normalized.put("recommendationId", recommendationId.toString());
    normalized.put("candidateFacilityId", destinationId.toString());
    return new NextRequestContent(
        expectedVersion.longValue(),
        revision.longValue(),
        recommendationId,
        directoryReference("Facility", destinationId),
        normalized);
  }

  private ClosureContent closureContent(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "The Journey closure body must be a JSON object.");
    }
    body.properties()
        .forEach(
            field -> {
              if (!CLOSURE_FIELDS.contains(field.getKey())) {
                throw invalid("The Journey closure contains an unsupported field.");
              }
            });
    JsonNode expectedVersion = body.get("expectedVersion");
    JsonNode reasonCode = body.get("reasonCode");
    JsonNode occurredAt = body.get("occurredAt");
    if (expectedVersion == null
        || !expectedVersion.isIntegralNumber()
        || !expectedVersion.canConvertToLong()
        || expectedVersion.longValue() < 1
        || reasonCode == null
        || !reasonCode.isTextual()
        || occurredAt == null
        || !occurredAt.isTextual()) {
      throw invalid(
          "A positive Journey version, structured reason and occurrence time are required.");
    }
    Instant parsedOccurredAt;
    try {
      parsedOccurredAt = Instant.parse(occurredAt.stringValue());
    } catch (java.time.format.DateTimeParseException malformed) {
      throw invalid("A valid closure occurrence time is required.");
    }
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", expectedVersion.longValue());
    normalized.put("reasonCode", reasonCode.stringValue());
    normalized.put("occurredAt", parsedOccurredAt.toString());
    return new ClosureContent(
        expectedVersion.longValue(), reasonCode.stringValue(), parsedOccurredAt, normalized);
  }

  private static InternalId parseId(JsonNode value, String label) {
    if (value == null || !value.isTextual()) {
      throw invalid("A valid " + label + " is required.");
    }
    try {
      return InternalId.parse(value.stringValue());
    } catch (IllegalArgumentException malformed) {
      throw invalid("A valid " + label + " is required.");
    }
  }

  private static InternalId parseId(String value, String label) {
    try {
      return InternalId.parse(value);
    } catch (IllegalArgumentException malformed) {
      throw invalid("A valid " + label + " is required.");
    }
  }

  private static ExternalReference directoryReference(String type, InternalId id) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), type, id.toString());
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSFER_REQUEST_INVALID, detail);
  }

  private static PlatformProblemException invalidScope() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Transfer request creation requires a validated Directory origin scope.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private record RequestContent(
      InternalId referralId,
      long referralRevision,
      InternalId recommendationId,
      ExternalReference candidateFacility,
      boolean transportRequired,
      ObjectNode normalized) {}

  private record NextRequestContent(
      long expectedVersion,
      long referralRevision,
      InternalId recommendationId,
      ExternalReference candidateFacility,
      ObjectNode normalized) {}

  private record ClosureContent(
      long expectedVersion, String reasonCode, Instant occurredAt, ObjectNode normalized) {}

  public record TransferJourneyClosureRepresentation(
      String journeyId,
      String referralId,
      String outcome,
      String reasonCode,
      String occurredAt,
      String recordedAt,
      long journeyVersion) {
    static TransferJourneyClosureRepresentation from(TransferJourneyClosureCommandResult result) {
      var closure = result.closure();
      return new TransferJourneyClosureRepresentation(
          closure.journeyId().toString(),
          closure.referralId().toString(),
          "UNFULFILLED",
          closure.reasonCode(),
          closure.occurredAt().toString(),
          closure.recordedAt().toString(),
          closure.journeyVersion());
    }
  }

  public record TransferJourneyRepresentation(
      String journeyId,
      String referralId,
      String status,
      int attemptNumber,
      String requestAttemptId,
      long referralRevision,
      String recommendationId,
      String destinationFacilityId,
      boolean transportRequired,
      String requestedAt,
      String expiresAt) {
    static TransferJourneyRepresentation from(TransferJourneyCommandResult result) {
      TransferRequestAttempt attempt = result.requestAttempt();
      return new TransferJourneyRepresentation(
          result.journey().id().toString(),
          result.journey().referralId().toString(),
          attempt.status().name(),
          attempt.attemptNumber(),
          attempt.id().toString(),
          attempt.referralRevision(),
          attempt.recommendationId().toString(),
          attempt.destinationFacility().value(),
          result.journey().transportRequired(),
          attempt.requestedAt().toString(),
          attempt.expiresAt().toString());
    }
  }
}
