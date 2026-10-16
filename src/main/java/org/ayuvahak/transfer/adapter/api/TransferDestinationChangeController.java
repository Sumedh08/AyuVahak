package org.ayuvahak.transfer.adapter.api;

import java.time.Instant;
import java.util.Optional;
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
import org.ayuvahak.transfer.application.TransferDestinationChangeCommandResult;
import org.ayuvahak.transfer.application.TransferDestinationChangeCommands;
import org.ayuvahak.transfer.application.TransferDestinationChangeReceivingCommands;
import org.ayuvahak.transfer.domain.DestinationChangeAssessment;
import org.ayuvahak.transfer.domain.ReceivingAssessment;
import org.ayuvahak.transfer.domain.TransferDestinationChangeOperation;
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

/** HTTP boundary for Transfer-owned post-movement destination-change intents. */
@RestController
@RequestMapping("/api/v1/transfers/{transferId}/destination-changes")
public final class TransferDestinationChangeController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "referralRevision",
          "recommendationId",
          "proposedOrganizationId",
          "proposedFacilityId",
          "reasonCode");
  private static final Set<String> ASSESSMENT_FIELDS =
      Set.of("expectedVersion", "outcome", "reasonCode", "evidenceReference", "occurredAt");
  private static final Set<String> ACCEPTANCE_FIELDS = Set.of("expectedVersion", "occurredAt");
  private static final Set<String> REJECTION_FIELDS =
      Set.of("expectedVersion", "reasonCode", "occurredAt");
  private static final java.util.regex.Pattern REASON_CODE =
      java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
  private static final java.util.regex.Pattern EVIDENCE_REFERENCE =
      java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

  private final TransferDestinationChangeCommands commands;
  private final TransferDestinationChangeReceivingCommands receivingCommands;
  private final ExecutionContextFactory contexts;
  private final ObjectMapper mapper;

  public TransferDestinationChangeController(
      TransferDestinationChangeCommands commands,
      TransferDestinationChangeReceivingCommands receivingCommands,
      ExecutionContextFactory contexts,
      ObjectMapper mapper) {
    this.commands = commands;
    this.receivingCommands = receivingCommands;
    this.contexts = contexts;
    this.mapper = mapper;
  }

  @PostMapping
  public ResponseEntity<DestinationChangeRepresentation> request(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    RequestContent request = read(body);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    TransferDestinationChangeCommandResult result =
        commands.request(
            context,
            parseId(transferId, "Transfer id"),
            request.referralRevision(),
            request.recommendationId(),
            request.proposedOrganization(),
            request.proposedFacility(),
            request.reasonCode(),
            idempotencyKey,
            RequestFingerprint.from(request.normalized()));
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        DestinationChangeRepresentation.from(result.operation()), headers, HttpStatus.CREATED);
  }

  @PostMapping("/{operationId}/clinical-suitability-assessments")
  public ResponseEntity<AssessmentRepresentation> clinicalSuitability(
      @PathVariable String transferId,
      @PathVariable String operationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    return recordAssessment(
        transferId,
        operationId,
        actor,
        scope,
        organizationId,
        facilityId,
        idempotencyKey,
        body,
        ReceivingAssessment.Kind.CLINICAL_SUITABILITY);
  }

  @PostMapping("/{operationId}/operational-readiness-assessments")
  public ResponseEntity<AssessmentRepresentation> operationalReadiness(
      @PathVariable String transferId,
      @PathVariable String operationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    return recordAssessment(
        transferId,
        operationId,
        actor,
        scope,
        organizationId,
        facilityId,
        idempotencyKey,
        body,
        ReceivingAssessment.Kind.OPERATIONAL_READINESS);
  }

  @PostMapping("/{operationId}/acceptance")
  public ResponseEntity<DecisionRepresentation> accept(
      @PathVariable String transferId,
      @PathVariable String operationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    requireFields(body, ACCEPTANCE_FIELDS);
    long version = expectedVersion(body);
    Instant occurredAt = parseInstant(body.get("occurredAt"));
    ObjectNode normalized = mapper.createObjectNode();
    normalized.put("expectedVersion", version);
    normalized.put("occurredAt", occurredAt.toString());
    var result =
        receivingCommands.accept(
            context(actor, scope, organizationId, facilityId),
            parseId(transferId, "Transfer id"),
            parseId(operationId, "destination-change operation id"),
            occurredAt,
            version,
            idempotencyKey,
            RequestFingerprint.from(normalized));
    return new ResponseEntity<>(
        new DecisionRepresentation(
            result.operation().id().toString(),
            "ACCEPTED",
            result.operation().status().name(),
            result.operation().version()),
        responseHeaders(result.replayed()),
        HttpStatus.CREATED);
  }

  @PostMapping("/{operationId}/rejection")
  public ResponseEntity<DecisionRepresentation> reject(
      @PathVariable String transferId,
      @PathVariable String operationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    requireFields(body, REJECTION_FIELDS);
    long version = expectedVersion(body);
    String reason = reasonCode(body.get("reasonCode"));
    Instant occurredAt = parseInstant(body.get("occurredAt"));
    ObjectNode normalized = mapper.createObjectNode();
    normalized.put("expectedVersion", version);
    normalized.put("reasonCode", reason);
    normalized.put("occurredAt", occurredAt.toString());
    var result =
        receivingCommands.reject(
            context(actor, scope, organizationId, facilityId),
            parseId(transferId, "Transfer id"),
            parseId(operationId, "destination-change operation id"),
            reason,
            occurredAt,
            version,
            idempotencyKey,
            RequestFingerprint.from(normalized));
    return new ResponseEntity<>(
        new DecisionRepresentation(
            result.operation().id().toString(),
            "REJECTED",
            result.operation().status().name(),
            result.operation().version()),
        responseHeaders(result.replayed()),
        HttpStatus.CREATED);
  }

  private ResponseEntity<AssessmentRepresentation> recordAssessment(
      String transferId,
      String operationId,
      ActorReference actor,
      String scope,
      String organizationId,
      String facilityId,
      String idempotencyKey,
      JsonNode body,
      ReceivingAssessment.Kind kind) {
    requireFields(body, ASSESSMENT_FIELDS);
    long version = expectedVersion(body);
    ReceivingAssessment.Outcome outcome =
        parseEnum(
            body.get("outcome"),
            ReceivingAssessment.Outcome.class,
            "A supported assessment outcome is required.");
    String reason = reasonCode(body.get("reasonCode"));
    Optional<String> evidence = optionalEvidence(body.get("evidenceReference"));
    Instant occurredAt = parseInstant(body.get("occurredAt"));
    ObjectNode normalized = mapper.createObjectNode();
    normalized.put("expectedVersion", version);
    normalized.put("outcome", outcome.name());
    normalized.put("reasonCode", reason);
    normalized.put("evidenceReference", evidence.orElse(null));
    normalized.put("occurredAt", occurredAt.toString());
    var result =
        receivingCommands.recordAssessment(
            context(actor, scope, organizationId, facilityId),
            parseId(transferId, "Transfer id"),
            parseId(operationId, "destination-change operation id"),
            kind,
            outcome,
            reason,
            evidence,
            occurredAt,
            version,
            idempotencyKey,
            RequestFingerprint.from(normalized));
    DestinationChangeAssessment assessment = result.assessment();
    return new ResponseEntity<>(
        new AssessmentRepresentation(
            assessment.id().toString(),
            assessment.operationId().toString(),
            assessment.kind().name(),
            assessment.outcome().name(),
            assessment.reasonCode(),
            assessment.evidenceReference().orElse(null),
            assessment.occurredAt().toString(),
            assessment.recordedAt().toString()),
        responseHeaders(result.replayed()),
        HttpStatus.CREATED);
  }

  private HttpHeaders responseHeaders(boolean replayed) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (replayed) headers.set("Idempotency-Replayed", "true");
    return headers;
  }

  private static void requireFields(JsonNode body, Set<String> allowedFields) {
    if (body == null || !body.isObject()) {
      throw invalid("A destination-change command body must be a JSON object.");
    }
    body.properties()
        .forEach(
            field -> {
              if (!allowedFields.contains(field.getKey())) {
                throw invalid("The destination-change command contains an unsupported field.");
              }
            });
  }

  private static long expectedVersion(JsonNode body) {
    JsonNode value = body.get("expectedVersion");
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 1) {
      throw invalid("A positive destination-change operation version is required.");
    }
    return value.longValue();
  }

  private static String reasonCode(JsonNode value) {
    if (value == null
        || !value.isTextual()
        || !REASON_CODE.matcher(value.stringValue()).matches()) {
      throw invalid("A structured destination-change reason code is required.");
    }
    return value.stringValue();
  }

  private static Optional<String> optionalEvidence(JsonNode value) {
    if (value == null || value.isNull()) return Optional.empty();
    if (!value.isTextual() || !EVIDENCE_REFERENCE.matcher(value.stringValue()).matches()) {
      throw invalid("Assessment evidence must be a minimal bounded reference.");
    }
    return Optional.of(value.stringValue());
  }

  private static Instant parseInstant(JsonNode value) {
    try {
      return Instant.parse(requiredText(value, "A valid occurrence time is required."));
    } catch (RuntimeException malformed) {
      throw invalid("A valid occurrence time is required.");
    }
  }

  private static String requiredText(JsonNode value, String message) {
    if (value == null || !value.isTextual() || value.stringValue().isBlank())
      throw invalid(message);
    return value.stringValue();
  }

  private static <T extends Enum<T>> T parseEnum(JsonNode value, Class<T> type, String message) {
    try {
      return Enum.valueOf(type, requiredText(value, message));
    } catch (RuntimeException malformed) {
      throw invalid(message);
    }
  }

  private RequestContent read(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "The destination-change request body must be a JSON object.");
    }
    body.properties()
        .forEach(
            field -> {
              if (!REQUEST_FIELDS.contains(field.getKey())) {
                throw invalid("The destination-change request contains an unsupported field.");
              }
            });
    JsonNode revision = body.get("referralRevision");
    JsonNode reason = body.get("reasonCode");
    if (revision == null
        || !revision.isIntegralNumber()
        || !revision.canConvertToLong()
        || revision.longValue() < 1
        || reason == null
        || !reason.isTextual()) {
      throw invalid("A positive Referral revision and coded reason are required.");
    }
    InternalId recommendationId = parseId(body.get("recommendationId"), "recommendation id");
    InternalId organizationId =
        parseId(body.get("proposedOrganizationId"), "proposed Organization id");
    InternalId facilityId = parseId(body.get("proposedFacilityId"), "proposed Facility id");
    ObjectNode normalized = mapper.createObjectNode();
    normalized.put("referralRevision", revision.longValue());
    normalized.put("recommendationId", recommendationId.toString());
    normalized.put("proposedOrganizationId", organizationId.toString());
    normalized.put("proposedFacilityId", facilityId.toString());
    normalized.put("reasonCode", reason.stringValue());
    return new RequestContent(
        revision.longValue(),
        recommendationId,
        directoryReference("Organization", organizationId),
        directoryReference("Facility", facilityId),
        reason.stringValue(),
        normalized);
  }

  private ExecutionContext context(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    if (actor == null) {
      throw problem(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified human actor is required.");
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
              directoryReference("Organization", InternalId.parse(organizationId)),
              directoryReference("Facility", InternalId.parse(facilityId))),
          correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static InternalId parseId(JsonNode value, String label) {
    if (value == null || !value.isTextual()) {
      throw invalid("A valid " + label + " is required.");
    }
    return parseId(value.stringValue(), label);
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

  private static PlatformProblemException invalidScope() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "A destination change requires a validated origin Facility scope.");
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSFER_REQUEST_INVALID, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private record RequestContent(
      long referralRevision,
      InternalId recommendationId,
      ExternalReference proposedOrganization,
      ExternalReference proposedFacility,
      String reasonCode,
      ObjectNode normalized) {}

  public record DestinationChangeRepresentation(
      String operationId,
      String transferId,
      String transportRequestId,
      String oldFacilityId,
      String proposedFacilityId,
      String status,
      long version) {
    private static DestinationChangeRepresentation from(
        TransferDestinationChangeOperation operation) {
      return new DestinationChangeRepresentation(
          operation.id().toString(),
          operation.transferId().toString(),
          operation.transportRequestId().toString(),
          operation.oldFacility().value(),
          operation.proposedFacility().value(),
          operation.status().name(),
          operation.version());
    }
  }

  public record AssessmentRepresentation(
      String assessmentId,
      String operationId,
      String assessmentKind,
      String outcome,
      String reasonCode,
      String evidenceReference,
      String occurredAt,
      String recordedAt) {}

  public record DecisionRepresentation(
      String operationId, String decision, String status, long version) {}
}
