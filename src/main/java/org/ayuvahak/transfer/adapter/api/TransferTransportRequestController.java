package org.ayuvahak.transfer.adapter.api;

import java.net.URI;
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
import org.ayuvahak.transfer.application.TransferTransportRequestCommands;
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

/** HTTP boundary for a human-authorized Transport request linked to an eligible Transfer. */
@RestController
@RequestMapping("/api/v1/transfers/{transferId}/transport-request")
public final class TransferTransportRequestController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private final TransferTransportRequestCommands commands;
  private final ExecutionContextFactory contexts;
  private final ObjectMapper mapper;

  public TransferTransportRequestController(
      TransferTransportRequestCommands commands,
      ExecutionContextFactory contexts,
      ObjectMapper mapper) {
    this.commands = commands;
    this.contexts = contexts;
    this.mapper = mapper;
  }

  @PostMapping
  public ResponseEntity<TransportRequestRepresentation> request(
      @PathVariable String transferId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    if (actor == null) {
      throw problem(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified actor is required.");
    }
    if (body == null || !body.isObject() || body.properties().iterator().hasNext()) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSPORT_REQUEST_INVALID,
          "Transport request initiation accepts only an empty JSON object.");
    }
    InternalId id = parseId(transferId);
    ExecutionContext context = executionContext(actor, scope, organizationId, facilityId);
    var normalized = mapper.createObjectNode();
    normalized.put("transferId", id.toString());
    var result = commands.request(context, id, idempotencyKey, RequestFingerprint.from(normalized));
    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(URI.create("/api/v1/transfers/" + id + "/transport-request"));
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        new TransportRequestRepresentation(
            result.transportRequestId().toString(),
            result.transferId().toString(),
            result.status(),
            result.coordinationOutcome()),
        headers,
        HttpStatus.CREATED);
  }

  private ExecutionContext executionContext(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    try {
      if (!"FACILITY".equals(scope) || organizationId == null || facilityId == null) {
        throw invalidScope();
      }
      return actor.kind() == ActorKind.HUMAN
          ? contexts.forInteractiveRequest(
              actor,
              RequestedExecutionScope.facility(
                  reference("Organization", organizationId), reference("Facility", facilityId)),
              correlationId())
          : contexts.forIntegrationRequest(
              actor,
              RequestedExecutionScope.facility(
                  reference("Organization", organizationId), reference("Facility", facilityId)),
              correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw invalidScope();
    }
  }

  private static ExternalReference reference(String type, String value) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(value).toString());
  }

  private static InternalId parseId(String value) {
    try {
      return InternalId.parse(value);
    } catch (IllegalArgumentException malformed) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.TRANSFER_REQUEST_INVALID,
          "A valid Transfer identifier is required.");
    }
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalidScope() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Transport initiation requires a validated origin Facility scope.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  public record TransportRequestRepresentation(
      String transportRequestId, String transferId, String status, String coordinationOutcome) {}
}
