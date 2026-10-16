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
import org.ayuvahak.transport.application.TransportCancellationCommands;
import org.ayuvahak.transport.domain.TransportCancellationOperation;
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

/** Authorized manual entry points for durable Transport cancellation operations. */
@RestController
@RequestMapping("/api/v1")
public final class TransportCancellationController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final Set<String> REQUEST_FIELDS = Set.of("reasonCode");
  private static final Set<String> OUTCOME_FIELDS =
      Set.of("outcome", "sourceNamespace", "sourceObservationId", "occurredAt");
  private static final Set<String> UNCERTAIN_FIELDS = Set.of("state", "reasonCode");

  private final TransportCancellationCommands commands;
  private final ExecutionContextFactory contexts;
  private final ObjectMapper mapper;

  public TransportCancellationController(
      TransportCancellationCommands commands,
      ExecutionContextFactory contexts,
      ObjectMapper mapper) {
    this.commands = commands;
    this.contexts = contexts;
    this.mapper = mapper;
  }

  @PostMapping("/transport-requests/{transportRequestId}/cancellation-operations")
  public ResponseEntity<CancellationRepresentation> request(
      @PathVariable String transportRequestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    Request request = read(body, REQUEST_FIELDS, Request.class);
    if (request.reasonCode() == null || !request.reasonCode().matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw invalid("A bounded coded cancellation reason is required.");
    }
    InternalId requestId = parseId(transportRequestId, "Transport request");
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    var result =
        commands.request(
            context,
            requestId,
            actor,
            request.reasonCode(),
            idempotencyKey,
            RequestFingerprint.from(mapper.valueToTree(request)));
    HttpHeaders headers = responseHeaders(result.commandReplayed());
    headers.setLocation(
        java.net.URI.create(
            "/api/v1/transport-cancellation-operations/" + result.operation().id()));
    return new ResponseEntity<>(
        CancellationRepresentation.from(result),
        headers,
        result.operation().state() == TransportCancellationOperation.State.PENDING
            ? HttpStatus.ACCEPTED
            : HttpStatus.CREATED);
  }

  @PostMapping("/transport-cancellation-operations/{cancellationOperationId}/outcome")
  public ResponseEntity<CancellationRepresentation> resolve(
      @PathVariable String cancellationOperationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    OutcomeRequest request = read(body, OUTCOME_FIELDS, OutcomeRequest.class);
    TransportCancellationOperation.Outcome outcome;
    try {
      outcome = TransportCancellationOperation.Outcome.valueOf(request.outcome());
    } catch (RuntimeException malformed) {
      throw invalid("The cancellation outcome is unsupported.");
    }
    if (request.occurredAt() == null) {
      throw invalid("A cancellation outcome source time is required.");
    }
    InternalId operationId = parseId(cancellationOperationId, "cancellation operation");
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    var input =
        new TransportCancellationCommands.ResolutionInput(
            outcome,
            request.sourceNamespace(),
            request.sourceObservationId(),
            request.occurredAt());
    var result =
        commands.resolve(
            context,
            operationId,
            input,
            idempotencyKey,
            RequestFingerprint.from(mapper.valueToTree(request)));
    return new ResponseEntity<>(
        CancellationRepresentation.from(result),
        responseHeaders(result.commandReplayed()),
        HttpStatus.CREATED);
  }

  @PostMapping("/transport-cancellation-operations/{cancellationOperationId}/uncertainty")
  public ResponseEntity<CancellationRepresentation> markUncertain(
      @PathVariable String cancellationOperationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    UncertainRequest request = read(body, UNCERTAIN_FIELDS, UncertainRequest.class);
    TransportCancellationOperation.State state;
    try {
      state = TransportCancellationOperation.State.valueOf(request.state());
    } catch (RuntimeException malformed) {
      throw invalid("The cancellation uncertainty state is unsupported.");
    }
    if (request.reasonCode() == null || !request.reasonCode().matches("[A-Z][A-Z0-9_]{1,63}")) {
      throw invalid("A bounded coded cancellation uncertainty reason is required.");
    }
    InternalId operationId = parseId(cancellationOperationId, "cancellation operation");
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    var normalized = mapper.valueToTree(request);
    var result =
        commands.markUncertain(
            context,
            operationId,
            state,
            request.reasonCode(),
            idempotencyKey,
            RequestFingerprint.from(normalized));
    return new ResponseEntity<>(
        CancellationRepresentation.from(result),
        responseHeaders(result.commandReplayed()),
        HttpStatus.ACCEPTED);
  }

  private <T> T read(JsonNode body, Set<String> allowed, Class<T> type) {
    if (body == null
        || !body.isObject()
        || body.properties().stream().anyMatch(field -> !allowed.contains(field.getKey()))) {
      throw invalid("The cancellation request contains unsupported or missing fields.");
    }
    try {
      T value = mapper.treeToValue(body, type);
      if (value == null) throw invalid("The cancellation request body is invalid.");
      return value;
    } catch (JacksonException | IllegalArgumentException malformed) {
      throw invalid("The cancellation request body is invalid.");
    }
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
      var requested =
          RequestedExecutionScope.facility(
              directoryReference("Organization", organizationId),
              directoryReference("Facility", facilityId));
      return actor.kind() == ActorKind.HUMAN
          ? contexts.forInteractiveRequest(actor, requested, correlationId())
          : contexts.forIntegrationRequest(actor, requested, correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static HttpHeaders responseHeaders(boolean replayed) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (replayed) headers.set("Idempotency-Replayed", "true");
    return headers;
  }

  private static ExternalReference directoryReference(String type, String value) {
    try {
      return new ExternalReference(
          new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(value).toString());
    } catch (RuntimeException malformed) {
      throw invalidScope();
    }
  }

  private static InternalId parseId(String value, String label) {
    try {
      return InternalId.parse(value);
    } catch (RuntimeException malformed) {
      throw invalid("A valid " + label + " identifier is required.");
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
        "Transport cancellation requires a validated origin Facility scope.");
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID, detail);
  }

  public record Request(String reasonCode) {}

  public record OutcomeRequest(
      String outcome, String sourceNamespace, String sourceObservationId, Instant occurredAt) {}

  public record UncertainRequest(String state, String reasonCode) {}

  public record CancellationRepresentation(
      String cancellationOperationId,
      String transportRequestId,
      String state,
      String outcome,
      String requestStatus) {
    private static CancellationRepresentation from(
        TransportCancellationCommands.CancellationResult result) {
      return new CancellationRepresentation(
          result.operation().id().toString(),
          result.operation().transportRequestId().toString(),
          result.operation().state().name(),
          result.operation().outcome().map(Enum::name).orElse(null),
          result.requestStatus().name());
    }
  }
}
