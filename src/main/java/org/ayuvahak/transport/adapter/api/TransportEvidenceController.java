package org.ayuvahak.transport.adapter.api;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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
import org.ayuvahak.transport.application.TransportEvidenceCommandResult;
import org.ayuvahak.transport.application.TransportEvidenceCommands;
import org.ayuvahak.transport.domain.TransportRequest;
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

/** Local/manual entry points for minimal, attributable Transport evidence. */
@RestController
@RequestMapping("/api/v1/transport-requests/{transportRequestId}")
public final class TransportEvidenceController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final Set<String> STATUS_FIELDS =
      Set.of(
          "observationId",
          "sourceNamespace",
          "sourceSequence",
          "status",
          "occurredAt",
          "reasonCode");
  private static final Set<String> ASSIGNMENT_FIELDS =
      Set.of("sourceNamespace", "sourceObservationId", "assignmentReference", "occurredAt");
  private static final Set<String> UNFULFILLED_FIELDS =
      Set.of(
          "sourceNamespace", "sourceObservationId", "sourceSequence", "occurredAt", "reasonCode");
  private static final Set<String> COMPLETION_FIELDS =
      Set.of(
          "sourceNamespace", "sourceObservationId", "sourceSequence", "occurredAt", "reasonCode");
  private static final Set<String> ETA_FIELDS =
      Set.of("observationId", "sourceNamespace", "estimatedArrivalAt", "observedAt");

  private final TransportEvidenceCommands commands;
  private final ExecutionContextFactory contexts;
  private final ObjectMapper mapper;

  public TransportEvidenceController(
      TransportEvidenceCommands commands, ExecutionContextFactory contexts, ObjectMapper mapper) {
    this.commands = commands;
    this.contexts = contexts;
    this.mapper = mapper;
  }

  @PostMapping("/status-observations")
  public ResponseEntity<EvidenceRepresentation> recordStatus(
      @PathVariable String transportRequestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    StatusRequest request = read(body, STATUS_FIELDS, StatusRequest.class);
    InternalId requestId = parseId(transportRequestId);
    TransportEvidenceCommands.StatusInput input = statusInput(request);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    var normalized = mapper.valueToTree(request);
    var result =
        commands.recordStatus(
            context, requestId, input, idempotencyKey, RequestFingerprint.from(normalized));
    return response(requestId, result);
  }

  @PostMapping("/unfulfilled")
  public ResponseEntity<EvidenceRepresentation> recordUnfulfilled(
      @PathVariable String transportRequestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    UnfulfilledRequest request = read(body, UNFULFILLED_FIELDS, UnfulfilledRequest.class);
    InternalId requestId = parseId(transportRequestId);
    validateSource(request.sourceNamespace(), request.sourceObservationId());
    if (request.occurredAt() == null
        || request.reasonCode() == null
        || !request.reasonCode().matches("[A-Z][A-Z0-9_]{1,63}")
        || (request.sourceSequence() != null && request.sourceSequence() < 0)) {
      throw invalid("A valid source time, sequence, and coded unfulfilled reason are required.");
    }
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    var normalized = mapper.valueToTree(request);
    var input =
        new TransportEvidenceCommands.StatusInput(
            request.sourceNamespace(),
            request.sourceObservationId(),
            request.sourceSequence() == null
                ? OptionalLong.empty()
                : OptionalLong.of(request.sourceSequence()),
            TransportRequest.Status.UNFULFILLED,
            request.occurredAt(),
            Optional.of(request.reasonCode()));
    var result =
        commands.recordUnfulfilled(
            context, requestId, input, idempotencyKey, RequestFingerprint.from(normalized));
    return response(requestId, result);
  }

  @PostMapping("/completion")
  public ResponseEntity<EvidenceRepresentation> complete(
      @PathVariable String transportRequestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    CompletionRequest request = read(body, COMPLETION_FIELDS, CompletionRequest.class);
    InternalId requestId = parseId(transportRequestId);
    validateSource(request.sourceNamespace(), request.sourceObservationId());
    if (request.occurredAt() == null
        || request.reasonCode() == null
        || !request.reasonCode().matches("[A-Z][A-Z0-9_]{1,63}")
        || (request.sourceSequence() != null && request.sourceSequence() < 0)) {
      throw invalid("A valid completion time, source sequence, and closure reason are required.");
    }
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    var normalized = mapper.valueToTree(request);
    var input =
        new TransportEvidenceCommands.StatusInput(
            request.sourceNamespace(),
            request.sourceObservationId(),
            request.sourceSequence() == null
                ? OptionalLong.empty()
                : OptionalLong.of(request.sourceSequence()),
            TransportRequest.Status.COMPLETED,
            request.occurredAt(),
            Optional.of(request.reasonCode()));
    var result =
        commands.recordCompletion(
            context, requestId, input, idempotencyKey, RequestFingerprint.from(normalized));
    return response(requestId, result);
  }

  @PostMapping("/assignments")
  public ResponseEntity<EvidenceRepresentation> recordAssignment(
      @PathVariable String transportRequestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    AssignmentRequest request = read(body, ASSIGNMENT_FIELDS, AssignmentRequest.class);
    InternalId requestId = parseId(transportRequestId);
    validateSource(request.sourceNamespace(), request.sourceObservationId());
    validateIdentifier(request.assignmentReference(), "assignment reference", 256);
    if (request.occurredAt() == null) {
      throw invalid("An assignment occurrence time is required.");
    }
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    var normalized = mapper.valueToTree(request);
    var input =
        new TransportEvidenceCommands.AssignmentInput(
            request.sourceNamespace(),
            request.sourceObservationId(),
            request.assignmentReference(),
            request.occurredAt());
    var result =
        commands.recordAssignment(
            context, requestId, input, idempotencyKey, RequestFingerprint.from(normalized));
    return response(requestId, result);
  }

  @PostMapping("/eta-observations")
  public ResponseEntity<EvidenceRepresentation> recordEta(
      @PathVariable String transportRequestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    EtaRequest request = read(body, ETA_FIELDS, EtaRequest.class);
    InternalId requestId = parseId(transportRequestId);
    validateSource(request.sourceNamespace(), request.observationId());
    if (request.estimatedArrivalAt() == null || request.observedAt() == null) {
      throw invalid("ETA estimate and observation times are required.");
    }
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    var normalized = mapper.valueToTree(request);
    var input =
        new TransportEvidenceCommands.EtaInput(
            request.sourceNamespace(),
            request.observationId(),
            request.estimatedArrivalAt(),
            request.observedAt());
    var result =
        commands.recordEta(
            context, requestId, input, idempotencyKey, RequestFingerprint.from(normalized));
    return response(requestId, result);
  }

  private <T> T read(JsonNode body, Set<String> allowedFields, Class<T> type) {
    if (body == null
        || !body.isObject()
        || body.properties().stream().anyMatch(field -> !allowedFields.contains(field.getKey()))) {
      throw invalid("The Transport evidence contains unsupported or missing fields.");
    }
    try {
      T value = mapper.treeToValue(body, type);
      if (value == null) {
        throw invalid("The Transport evidence body is invalid.");
      }
      return value;
    } catch (JacksonException | IllegalArgumentException malformed) {
      throw invalid("The Transport evidence body is invalid.");
    }
  }

  private TransportEvidenceCommands.StatusInput statusInput(StatusRequest request) {
    validateSource(request.sourceNamespace(), request.observationId());
    if (request.occurredAt() == null) {
      throw invalid("A source occurrence time is required.");
    }
    if (request.sourceSequence() != null && request.sourceSequence() < 0) {
      throw invalid("The source sequence must not be negative.");
    }
    if (request.reasonCode() != null && !request.reasonCode().matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw invalid("The reason code is invalid.");
    }
    TransportRequest.Status status;
    try {
      status = TransportRequest.Status.valueOf(request.status());
    } catch (IllegalArgumentException | NullPointerException malformed) {
      throw invalid("The Transport status is unsupported.");
    }
    if (!Set.of(
            TransportRequest.Status.AWAITING_ASSIGNMENT,
            TransportRequest.Status.EN_ROUTE_TO_ORIGIN,
            TransportRequest.Status.AT_ORIGIN,
            TransportRequest.Status.PATIENT_ONBOARD,
            TransportRequest.Status.EN_ROUTE_TO_DESTINATION,
            TransportRequest.Status.ARRIVED_DESTINATION,
            TransportRequest.Status.INTERRUPTED)
        .contains(status)) {
      throw invalid("This status must be established through its dedicated Transport operation.");
    }
    return new TransportEvidenceCommands.StatusInput(
        request.sourceNamespace(),
        request.observationId(),
        request.sourceSequence() == null
            ? OptionalLong.empty()
            : OptionalLong.of(request.sourceSequence()),
        status,
        request.occurredAt(),
        Optional.ofNullable(request.reasonCode()));
  }

  private ExecutionContext context(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    if (actor == null) {
      throw new PlatformProblemException(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified actor is required.");
    }
    if (!"FACILITY".equals(scope) || organizationId == null || facilityId == null) {
      throw invalidScope();
    }
    try {
      RequestedExecutionScope requested =
          RequestedExecutionScope.facility(
              directoryReference("Organization", organizationId),
              directoryReference("Facility", facilityId));
      return actor.kind() == ActorKind.HUMAN
          ? contexts.forInteractiveRequest(actor, requested, correlationId())
          : contexts.forIntegrationRequest(actor, requested, correlationId());
    } catch (DirectoryScopeLookupUnavailableException unavailable) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSPORT_UNAVAILABLE,
          "Directory could not validate the Transport execution scope.");
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static void validateSource(String namespace, String observationId) {
    if (namespace == null
        || namespace.length() > 256
        || !namespace.matches("[A-Za-z][A-Za-z0-9+.-]*:[^\\s\\p{Cntrl}]{1,240}")) {
      throw invalid("A bounded source namespace URI is required.");
    }
    validateIdentifier(observationId, "source observation identifier", 128);
  }

  private static void validateIdentifier(String value, String label, int maxLength) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > maxLength
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid("A bounded " + label + " is required.");
    }
  }

  private static ResponseEntity<EvidenceRepresentation> response(
      InternalId requestId, TransportEvidenceCommandResult result) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    if (result.sourceReplayed()) {
      headers.set("Source-Observation-Replayed", "true");
    }
    return new ResponseEntity<>(
        new EvidenceRepresentation(
            result.observationId().toString(),
            result.transportRequestId().toString(),
            result.requestStatus(),
            result.evidenceOutcome(),
            result.validUntil().map(Instant::toString).orElse(null)),
        headers,
        HttpStatus.CREATED);
  }

  private static ExternalReference directoryReference(String type, String value) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(value).toString());
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static InternalId parseId(String serialized) {
    try {
      return InternalId.parse(serialized);
    } catch (RuntimeException malformed) {
      throw invalid("A valid Transport request identifier is required.");
    }
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Transport evidence requires a validated origin Facility scope.");
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID, detail);
  }

  public record StatusRequest(
      String observationId,
      String sourceNamespace,
      Long sourceSequence,
      String status,
      Instant occurredAt,
      String reasonCode) {}

  public record AssignmentRequest(
      String sourceNamespace,
      String sourceObservationId,
      String assignmentReference,
      Instant occurredAt) {}

  public record UnfulfilledRequest(
      String sourceNamespace,
      String sourceObservationId,
      Long sourceSequence,
      Instant occurredAt,
      String reasonCode) {}

  public record CompletionRequest(
      String sourceNamespace,
      String sourceObservationId,
      Long sourceSequence,
      Instant occurredAt,
      String reasonCode) {}

  public record EtaRequest(
      String observationId,
      String sourceNamespace,
      Instant estimatedArrivalAt,
      Instant observedAt) {}

  public record EvidenceRepresentation(
      String observationId,
      String transportRequestId,
      String requestStatus,
      String evidenceOutcome,
      String validUntil) {}
}
