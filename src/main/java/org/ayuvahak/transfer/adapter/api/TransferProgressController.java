package org.ayuvahak.transfer.adapter.api;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
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
import org.ayuvahak.transfer.application.TransferProgressCommandResult;
import org.ayuvahak.transfer.application.TransferProgressCommands;
import org.ayuvahak.transfer.application.TransferProgressCorrectionResult;
import org.ayuvahak.transfer.application.TransferProgressHistory;
import org.ayuvahak.transfer.domain.TransferProgressCorrection;
import org.ayuvahak.transfer.domain.TransferProgressFact;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** HTTP boundary for attributable Transfer departure, movement, Arrival, and closure facts. */
@RestController
@RequestMapping("/api/v1/transfers/{transferId}")
public final class TransferProgressController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private static final String ORGANIZATION_SCOPE_HEADER = "X-AyuVahak-Organization-Id";
  private static final String FACILITY_SCOPE_HEADER = "X-AyuVahak-Facility-Id";
  private static final Set<String> REQUEST_FIELDS =
      Set.of("expectedVersion", "occurredAt", "reasonCode", "sourceReference");
  private static final Set<String> CORRECTION_FIELDS =
      Set.of(
          "expectedVersion",
          "correctionKind",
          "correctedOccurredAt",
          "correctedResultCode",
          "reasonCode");

  private final TransferProgressCommands commands;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;

  public TransferProgressController(
      TransferProgressCommands commands,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper) {
    this.commands = commands;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/departure")
  public ResponseEntity<ProgressRepresentation> departure(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    return record(
        transferId,
        actor,
        scope,
        organizationId,
        facilityId,
        idempotencyKey,
        body,
        TransferProgressFact.Kind.DEPARTURE);
  }

  @PostMapping("/interruption")
  public ResponseEntity<ProgressRepresentation> interruption(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    return record(
        transferId,
        actor,
        scope,
        organizationId,
        facilityId,
        idempotencyKey,
        body,
        TransferProgressFact.Kind.INTERRUPTION);
  }

  @PostMapping("/resumption")
  public ResponseEntity<ProgressRepresentation> resumption(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    return record(
        transferId,
        actor,
        scope,
        organizationId,
        facilityId,
        idempotencyKey,
        body,
        TransferProgressFact.Kind.RESUMPTION);
  }

  @PostMapping("/arrival")
  public ResponseEntity<ProgressRepresentation> arrival(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    return record(
        transferId,
        actor,
        scope,
        organizationId,
        facilityId,
        idempotencyKey,
        body,
        TransferProgressFact.Kind.ARRIVAL);
  }

  @PostMapping("/handover")
  public ResponseEntity<ProgressRepresentation> handover(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    return record(
        transferId,
        actor,
        scope,
        organizationId,
        facilityId,
        idempotencyKey,
        body,
        TransferProgressFact.Kind.HANDOVER);
  }

  @PostMapping("/completion")
  public ResponseEntity<ProgressRepresentation> completion(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    return record(
        transferId,
        actor,
        scope,
        organizationId,
        facilityId,
        idempotencyKey,
        body,
        TransferProgressFact.Kind.COMPLETION);
  }

  @GetMapping("/progress")
  public TransferHistoryRepresentation progress(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId) {
    TransferProgressHistory history =
        commands.history(
            executionContext(actor, scope, organizationId, facilityId),
            parseId(transferId, "Transfer id"));
    return TransferHistoryRepresentation.from(history);
  }

  @PostMapping("/progress/{progressFactId}/corrections")
  public ResponseEntity<CorrectionRepresentation> correctProgress(
      @PathVariable String transferId,
      @PathVariable String progressFactId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String scope,
      @RequestHeader(value = ORGANIZATION_SCOPE_HEADER, required = false) String organizationId,
      @RequestHeader(value = FACILITY_SCOPE_HEADER, required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    InternalId transfer = parseId(transferId, "Transfer id");
    InternalId fact = parseId(progressFactId, "progress fact id");
    CorrectionContent content = correctionContent(body);
    TransferProgressCorrectionResult result =
        commands.correct(
            executionContext(actor, scope, organizationId, facilityId),
            transfer,
            fact,
            content.kind(),
            content.correctedOccurredAt(),
            content.correctedResultCode(),
            content.reasonCode(),
            content.expectedVersion(),
            idempotencyKey,
            RequestFingerprint.from(content.normalized()));
    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(URI.create("/api/v1/transfers/" + transfer + "/progress"));
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        CorrectionRepresentation.from(result.correction(), result.commandReplayed()),
        headers,
        HttpStatus.CREATED);
  }

  private ResponseEntity<ProgressRepresentation> record(
      String transferId,
      ActorReference actor,
      String scope,
      String organizationId,
      String facilityId,
      String idempotencyKey,
      JsonNode body,
      TransferProgressFact.Kind kind) {
    InternalId id = parseId(transferId, "Transfer id");
    RequestContent content = requestContent(body);
    TransferProgressCommandResult result =
        commands.record(
            executionContext(actor, scope, organizationId, facilityId),
            id,
            kind,
            content.occurredAt(),
            content.reasonCode(),
            content.sourceReference(),
            content.expectedVersion(),
            idempotencyKey,
            RequestFingerprint.from(content.normalized()));
    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(URI.create("/api/v1/transfers/" + id + "/progress"));
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        ProgressRepresentation.from(result.fact(), result.commandReplayed()),
        headers,
        HttpStatus.CREATED);
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
      if (!"FACILITY".equals(scope) || organizationId == null || facilityId == null) {
        throw invalidScope();
      }
      RequestedExecutionScope requested =
          RequestedExecutionScope.facility(
              directoryReference("Organization", InternalId.parse(organizationId)),
              directoryReference("Facility", InternalId.parse(facilityId)));
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
          "Transfer progress requires a JSON object.");
    }
    body.properties()
        .forEach(
            field -> {
              if (!REQUEST_FIELDS.contains(field.getKey())) {
                throw invalid("Transfer progress contains an unsupported field.");
              }
            });
    JsonNode expectedVersion = body.get("expectedVersion");
    if (expectedVersion == null
        || !expectedVersion.isIntegralNumber()
        || !expectedVersion.canConvertToLong()
        || expectedVersion.longValue() < 1) {
      throw invalid("A positive expected Transfer version is required.");
    }
    JsonNode occurredAtNode = body.get("occurredAt");
    if (occurredAtNode == null || !occurredAtNode.isTextual()) {
      throw invalid("A factual occurredAt timestamp is required.");
    }
    Instant occurredAt;
    try {
      occurredAt = Instant.parse(occurredAtNode.stringValue());
    } catch (DateTimeParseException malformed) {
      throw invalid("A valid UTC occurredAt timestamp is required.");
    }
    Optional<String> reasonCode = optionalText(body, "reasonCode");
    Optional<String> sourceReference = optionalText(body, "sourceReference");
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", expectedVersion.longValue());
    normalized.put("occurredAt", occurredAt.toString());
    reasonCode.ifPresent(value -> normalized.put("reasonCode", value));
    sourceReference.ifPresent(value -> normalized.put("sourceReference", value));
    return new RequestContent(
        expectedVersion.longValue(), occurredAt, reasonCode, sourceReference, normalized);
  }

  private CorrectionContent correctionContent(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "A Transfer progress correction requires a JSON object.");
    }
    body.properties()
        .forEach(
            field -> {
              if (!CORRECTION_FIELDS.contains(field.getKey())) {
                throw invalid("Transfer progress correction contains an unsupported field.");
              }
            });
    JsonNode expectedVersion = body.get("expectedVersion");
    if (expectedVersion == null
        || !expectedVersion.isIntegralNumber()
        || !expectedVersion.canConvertToLong()
        || expectedVersion.longValue() < 2) {
      throw invalid("A positive expected Transfer version is required.");
    }
    JsonNode kindNode = body.get("correctionKind");
    if (kindNode == null || !kindNode.isTextual()) {
      throw invalid("A correctionKind is required.");
    }
    TransferProgressCorrection.Kind kind;
    try {
      kind = TransferProgressCorrection.Kind.valueOf(kindNode.stringValue());
    } catch (IllegalArgumentException malformed) {
      throw invalid("A supported correctionKind is required.");
    }
    Optional<Instant> correctedOccurredAt = Optional.empty();
    JsonNode correctedAtNode = body.get("correctedOccurredAt");
    if (correctedAtNode != null) {
      if (!correctedAtNode.isTextual()) {
        throw invalid("A valid correctedOccurredAt is required when provided.");
      }
      try {
        correctedOccurredAt = Optional.of(Instant.parse(correctedAtNode.stringValue()));
      } catch (DateTimeParseException malformed) {
        throw invalid("A valid UTC correctedOccurredAt timestamp is required.");
      }
    }
    Optional<String> correctedResultCode = optionalText(body, "correctedResultCode");
    Optional<String> reason = optionalText(body, "reasonCode");
    if (reason.isEmpty()) {
      throw invalid("A structured reasonCode is required for a correction.");
    }
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", expectedVersion.longValue());
    normalized.put("correctionKind", kind.name());
    correctedOccurredAt.ifPresent(value -> normalized.put("correctedOccurredAt", value.toString()));
    correctedResultCode.ifPresent(value -> normalized.put("correctedResultCode", value));
    normalized.put("reasonCode", reason.orElseThrow());
    return new CorrectionContent(
        expectedVersion.longValue(),
        kind,
        correctedOccurredAt,
        correctedResultCode,
        reason.orElseThrow(),
        normalized);
  }

  private static Optional<String> optionalText(JsonNode body, String name) {
    JsonNode value = body.get(name);
    if (value == null) {
      return Optional.empty();
    }
    if (!value.isTextual()) {
      throw invalid("A valid " + name + " is required when provided.");
    }
    return Optional.of(value.stringValue());
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
        "Transfer progress requires a validated Directory Facility scope.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private record RequestContent(
      long expectedVersion,
      Instant occurredAt,
      Optional<String> reasonCode,
      Optional<String> sourceReference,
      ObjectNode normalized) {}

  private record CorrectionContent(
      long expectedVersion,
      TransferProgressCorrection.Kind kind,
      Optional<Instant> correctedOccurredAt,
      Optional<String> correctedResultCode,
      String reasonCode,
      ObjectNode normalized) {}

  public record ProgressRepresentation(
      String progressFactId,
      String transferId,
      String journeyId,
      String kind,
      String previousStatus,
      String status,
      String exceptionCode,
      String reasonCode,
      String sourceReference,
      String occurredAt,
      String recordedAt,
      long version,
      long aggregateSequence,
      boolean commandReplayed) {
    static ProgressRepresentation from(TransferProgressFact fact, boolean replayed) {
      return new ProgressRepresentation(
          fact.id().toString(),
          fact.transferId().toString(),
          fact.journeyId().toString(),
          fact.kind().name(),
          fact.previousStatus().map(Enum::name).orElse(null),
          fact.status().name(),
          fact.exceptionCode().orElse(null),
          fact.reasonCode().orElse(null),
          fact.sourceReference().orElse(null),
          fact.occurredAt().toString(),
          fact.recordedAt().toString(),
          fact.transferVersion(),
          fact.aggregateSequence(),
          replayed);
    }
  }

  public record TransferHistoryRepresentation(
      String journeyId,
      String transferId,
      String status,
      long version,
      List<ProgressRepresentation> facts,
      List<CorrectionRepresentation> corrections) {
    static TransferHistoryRepresentation from(TransferProgressHistory history) {
      return new TransferHistoryRepresentation(
          history.journeyId().toString(),
          history.transfer().id().toString(),
          history.transfer().status().name(),
          history.transfer().version(),
          history.facts().stream().map(fact -> ProgressRepresentation.from(fact, false)).toList(),
          history.corrections().stream()
              .map(correction -> CorrectionRepresentation.from(correction, false))
              .toList());
    }
  }

  public record CorrectionRepresentation(
      String correctionId,
      String transferId,
      String journeyId,
      String correctsProgressFactId,
      String correctionKind,
      String correctedOccurredAt,
      String correctedResultCode,
      String reasonCode,
      String recordedAt,
      String authorityCode,
      long transferVersion,
      long aggregateSequence,
      boolean commandReplayed) {
    static CorrectionRepresentation from(TransferProgressCorrection correction, boolean replayed) {
      return new CorrectionRepresentation(
          correction.id().toString(),
          correction.transferId().toString(),
          correction.journeyId().toString(),
          correction.correctsProgressFactId().toString(),
          correction.kind().name(),
          correction.correctedOccurredAt().map(Instant::toString).orElse(null),
          correction.correctedResultCode().orElse(null),
          correction.reasonCode(),
          correction.recordedAt().toString(),
          correction.authorityCode(),
          correction.transferVersion(),
          correction.aggregateSequence(),
          replayed);
    }
  }
}
