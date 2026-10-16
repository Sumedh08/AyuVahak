package org.ayuvahak.transport.adapter.api;

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
import org.ayuvahak.transport.application.TransportDestinationChangeCommands;
import org.ayuvahak.transport.domain.TransportDestinationChangeOperation;
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

/** HTTP boundary for an authorized manual Transport destination-change acknowledgement. */
@RestController
@RequestMapping("/api/v1/transport-destination-change-operations/{operationId}")
public final class TransportDestinationChangeController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final Set<String> OUTCOME_FIELDS =
      Set.of("outcome", "sourceNamespace", "sourceObservationId", "occurredAt");
  private final TransportDestinationChangeCommands commands;
  private final ExecutionContextFactory contexts;
  private final ObjectMapper mapper;

  public TransportDestinationChangeController(
      TransportDestinationChangeCommands commands,
      ExecutionContextFactory contexts,
      ObjectMapper mapper) {
    this.commands = commands;
    this.contexts = contexts;
    this.mapper = mapper;
  }

  @PostMapping("/outcome")
  public ResponseEntity<DestinationChangeRepresentation> resolve(
      @PathVariable String operationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestBody JsonNode body) {
    OutcomeRequest request = read(body);
    TransportDestinationChangeOperation.Outcome outcome;
    try {
      outcome = TransportDestinationChangeOperation.Outcome.valueOf(request.outcome());
    } catch (RuntimeException malformed) {
      throw invalid("The destination-change outcome is unsupported.");
    }
    if (request.occurredAt() == null) {
      throw invalid("A source occurrence time is required.");
    }
    InternalId id = parseId(operationId);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    var result =
        commands.resolve(
            context,
            id,
            outcome,
            request.sourceNamespace(),
            request.sourceObservationId(),
            request.occurredAt(),
            RequestFingerprint.from(mapper.valueToTree(request)));
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) headers.set("Idempotency-Replayed", "true");
    return new ResponseEntity<>(
        DestinationChangeRepresentation.from(result.operation()), headers, HttpStatus.CREATED);
  }

  private OutcomeRequest read(JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.properties().stream().anyMatch(field -> !OUTCOME_FIELDS.contains(field.getKey()))) {
      throw invalid("The destination-change outcome contains unsupported or missing fields.");
    }
    try {
      OutcomeRequest request = mapper.treeToValue(body, OutcomeRequest.class);
      if (request == null
          || request.outcome() == null
          || request.sourceNamespace() == null
          || request.sourceObservationId() == null) {
        throw invalid("The destination-change outcome is incomplete.");
      }
      return request;
    } catch (JacksonException | IllegalArgumentException malformed) {
      throw invalid("The destination-change outcome is invalid.");
    }
  }

  private ExecutionContext context(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    if (actor == null) {
      throw new PlatformProblemException(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified Transport operator is required.");
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
              reference("Organization", organizationId), reference("Facility", facilityId)),
          correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static ExternalReference reference(String type, String id) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(id).toString());
  }

  private static InternalId parseId(String value) {
    try {
      return InternalId.parse(value);
    } catch (RuntimeException malformed) {
      throw invalid("A valid destination-change operation identifier is required.");
    }
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Transport destination-change outcomes require a validated Facility scope.");
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID, detail);
  }

  public record OutcomeRequest(
      String outcome, String sourceNamespace, String sourceObservationId, Instant occurredAt) {}

  public record DestinationChangeRepresentation(
      String operationId,
      String transferDestinationChangeId,
      String transferId,
      String transportRequestId,
      String state,
      String outcome) {
    private static DestinationChangeRepresentation from(
        TransportDestinationChangeOperation operation) {
      return new DestinationChangeRepresentation(
          operation.id().toString(),
          operation.transferDestinationChangeId().toString(),
          operation.transferId().toString(),
          operation.transportRequestId().toString(),
          operation.state().name(),
          operation.outcome().map(Enum::name).orElse(null));
    }
  }
}
