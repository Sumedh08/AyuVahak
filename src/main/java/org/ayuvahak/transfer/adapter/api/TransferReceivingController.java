package org.ayuvahak.transfer.adapter.api;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
import org.ayuvahak.transfer.application.ReceivingAssessmentCommandResult;
import org.ayuvahak.transfer.application.TransferDecisionCommandResult;
import org.ayuvahak.transfer.application.TransferReceivingCommands;
import org.ayuvahak.transfer.domain.ReceivingAssessment;
import org.ayuvahak.transfer.domain.TransferDecision;
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

/** HTTP boundary for separately authorized receiving assessments and explicit decisions. */
@RestController
@RequestMapping("/api/v1/transfer-request-attempts/{attemptId}")
public final class TransferReceivingController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> ASSESSMENT_FIELDS =
      Set.of("expectedVersion", "outcome", "reasonCode", "evidenceReference", "occurredAt");
  private static final Set<String> DECISION_FIELDS = Set.of("expectedVersion", "occurredAt");
  private static final Set<String> REJECTION_FIELDS =
      Set.of("expectedVersion", "reasonCode", "occurredAt");
  private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
  private static final Pattern EVIDENCE_REFERENCE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

  private final TransferReceivingCommands commands;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;

  public TransferReceivingController(
      TransferReceivingCommands commands,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper) {
    this.commands = commands;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/clinical-suitability-assessments")
  public ResponseEntity<AssessmentRepresentation> clinicalSuitability(
      @PathVariable String attemptId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    AssessmentContent content = assessmentContent(body);
    var result =
        commands.recordAssessment(
            executionContext(actor, scope, organizationId, facilityId),
            parseAttemptId(attemptId),
            ReceivingAssessment.Kind.CLINICAL_SUITABILITY,
            content.outcome(),
            content.reasonCode(),
            content.evidenceReference(),
            content.occurredAt(),
            content.expectedVersion(),
            idempotencyKey,
            RequestFingerprint.from(content.normalized()));
    return assessmentResponse(result);
  }

  @PostMapping("/operational-readiness-assessments")
  public ResponseEntity<AssessmentRepresentation> operationalReadiness(
      @PathVariable String attemptId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    AssessmentContent content = assessmentContent(body);
    var result =
        commands.recordAssessment(
            executionContext(actor, scope, organizationId, facilityId),
            parseAttemptId(attemptId),
            ReceivingAssessment.Kind.OPERATIONAL_READINESS,
            content.outcome(),
            content.reasonCode(),
            content.evidenceReference(),
            content.occurredAt(),
            content.expectedVersion(),
            idempotencyKey,
            RequestFingerprint.from(content.normalized()));
    return assessmentResponse(result);
  }

  @PostMapping("/acceptance")
  public ResponseEntity<DecisionRepresentation> accept(
      @PathVariable String attemptId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    DecisionContent content = decisionContent(body, DECISION_FIELDS, false);
    var result =
        commands.accept(
            executionContext(actor, scope, organizationId, facilityId),
            parseAttemptId(attemptId),
            content.occurredAt(),
            content.expectedVersion(),
            idempotencyKey,
            RequestFingerprint.from(content.normalized()));
    return decisionResponse(result);
  }

  @PostMapping("/rejection")
  public ResponseEntity<DecisionRepresentation> reject(
      @PathVariable String attemptId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    DecisionContent content = decisionContent(body, REJECTION_FIELDS, true);
    var result =
        commands.reject(
            executionContext(actor, scope, organizationId, facilityId),
            parseAttemptId(attemptId),
            content.reasonCode(),
            content.occurredAt(),
            content.expectedVersion(),
            idempotencyKey,
            RequestFingerprint.from(content.normalized()));
    return decisionResponse(result);
  }

  private ResponseEntity<AssessmentRepresentation> assessmentResponse(
      ReceivingAssessmentCommandResult result) {
    ReceivingAssessment assessment = result.assessment();
    HttpHeaders headers = responseHeaders(result.commandReplayed());
    return new ResponseEntity<>(
        new AssessmentRepresentation(
            assessment.id().toString(),
            assessment.requestAttemptId().toString(),
            assessment.kind().name(),
            assessment.outcome().name(),
            assessment.reasonCode(),
            assessment.evidenceReference().orElse(null),
            assessment.occurredAt().toString(),
            assessment.recordedAt().toString(),
            assessment.requestVersion()),
        headers,
        HttpStatus.CREATED);
  }

  private ResponseEntity<DecisionRepresentation> decisionResponse(
      TransferDecisionCommandResult result) {
    TransferDecision decision = result.decision();
    HttpHeaders headers = responseHeaders(result.commandReplayed());
    String status = result.transfer().map(transfer -> transfer.status().name()).orElse("REJECTED");
    return new ResponseEntity<>(
        new DecisionRepresentation(
            decision.id().toString(),
            decision.requestAttemptId().toString(),
            decision.kind().name(),
            status,
            decision.reasonCode().orElse(null),
            result.transfer().map(transfer -> transfer.id().toString()).orElse(null),
            decision.occurredAt().toString(),
            decision.recordedAt().toString(),
            decision.requestVersion()),
        headers,
        HttpStatus.CREATED);
  }

  private HttpHeaders responseHeaders(boolean replayed) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (replayed) {
      headers.set("Idempotency-Replayed", "true");
    }
    return headers;
  }

  private AssessmentContent assessmentContent(JsonNode body) {
    requireFields(body, ASSESSMENT_FIELDS);
    long version = expectedVersion(body);
    ReceivingAssessment.Outcome outcome =
        parseEnum(
            body.get("outcome"),
            ReceivingAssessment.Outcome.class,
            "A supported assessment outcome is required.");
    String reasonCode =
        structuredReason(body.get("reasonCode"), "A structured assessment reason is required.");
    Optional<String> evidenceReference = optionalText(body.get("evidenceReference"));
    Instant occurredAt = parseInstant(body.get("occurredAt"));
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", version);
    normalized.put("outcome", outcome.name());
    normalized.put("reasonCode", reasonCode);
    if (evidenceReference.isPresent()) {
      normalized.put("evidenceReference", evidenceReference.orElseThrow());
    } else {
      normalized.putNull("evidenceReference");
    }
    normalized.put("occurredAt", occurredAt.toString());
    return new AssessmentContent(
        version, outcome, reasonCode, evidenceReference, occurredAt, normalized);
  }

  private DecisionContent decisionContent(JsonNode body, Set<String> fields, boolean rejection) {
    requireFields(body, fields);
    long version = expectedVersion(body);
    Instant occurredAt = parseInstant(body.get("occurredAt"));
    String reasonCode =
        rejection
            ? structuredReason(body.get("reasonCode"), "A structured rejection reason is required.")
            : null;
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", version);
    normalized.put("occurredAt", occurredAt.toString());
    if (rejection) {
      normalized.put("reasonCode", reasonCode);
    }
    return new DecisionContent(version, reasonCode, occurredAt, normalized);
  }

  private static void requireFields(JsonNode body, Set<String> allowedFields) {
    if (body == null || !body.isObject()) {
      throw invalid("A Transfer command body must be a JSON object.");
    }
    body.properties()
        .forEach(
            field -> {
              if (!allowedFields.contains(field.getKey())) {
                throw invalid("The Transfer command contains an unsupported field.");
              }
            });
  }

  private static long expectedVersion(JsonNode body) {
    JsonNode version = body.get("expectedVersion");
    if (version == null
        || !version.isIntegralNumber()
        || !version.canConvertToLong()
        || version.longValue() < 1) {
      throw invalid("A positive expected request version is required.");
    }
    return version.longValue();
  }

  private static String requiredText(JsonNode value, String message) {
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw invalid(message);
    }
    return value.textValue();
  }

  private static Optional<String> optionalText(JsonNode value) {
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isTextual()) {
      throw invalid("The evidence reference must be a minimal text identifier.");
    }
    String reference = value.textValue();
    if (!EVIDENCE_REFERENCE.matcher(reference).matches()) {
      throw invalid("The evidence reference must be a minimal text identifier.");
    }
    return Optional.of(reference);
  }

  private static String structuredReason(JsonNode value, String message) {
    String reason = requiredText(value, message);
    if (!REASON_CODE.matcher(reason).matches()) {
      throw invalid(message);
    }
    return reason;
  }

  private static Instant parseInstant(JsonNode value) {
    try {
      return Instant.parse(requiredText(value, "A valid occurrence time is required."));
    } catch (RuntimeException malformed) {
      throw invalid("A valid occurrence time is required.");
    }
  }

  private static <T extends Enum<T>> T parseEnum(JsonNode value, Class<T> type, String message) {
    try {
      return Enum.valueOf(type, requiredText(value, message));
    } catch (RuntimeException malformed) {
      throw invalid(message);
    }
  }

  private ExecutionContext executionContext(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    if (actor == null) {
      throw problem(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified actor is required.");
    }
    try {
      RequestedExecutionScope requested;
      if ("FACILITY".equals(scope) && organizationId != null && facilityId != null) {
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

  private static InternalId parseAttemptId(String value) {
    try {
      return InternalId.parse(value);
    } catch (IllegalArgumentException malformed) {
      throw invalid("A valid request attempt identifier is required.");
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
        "Receiving commands require a validated Directory Facility scope.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private record AssessmentContent(
      long expectedVersion,
      ReceivingAssessment.Outcome outcome,
      String reasonCode,
      Optional<String> evidenceReference,
      Instant occurredAt,
      ObjectNode normalized) {}

  private record DecisionContent(
      long expectedVersion, String reasonCode, Instant occurredAt, ObjectNode normalized) {}

  public record AssessmentRepresentation(
      String assessmentId,
      String requestAttemptId,
      String assessmentKind,
      String outcome,
      String reasonCode,
      String evidenceReference,
      String occurredAt,
      String recordedAt,
      long requestVersion) {}

  public record DecisionRepresentation(
      String decisionId,
      String requestAttemptId,
      String decisionKind,
      String status,
      String reasonCode,
      String transferId,
      String occurredAt,
      String recordedAt,
      long requestVersion) {}
}
