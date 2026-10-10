package org.ayuvahak.transfer.adapter.api;

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
import org.ayuvahak.transfer.application.TransferRequestLifecycleCommandResult;
import org.ayuvahak.transfer.application.TransferRequestLifecycleCommands;
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

/** HTTP boundary for sender-authorized withdrawal; expiry remains an internal owner command. */
@RestController
@RequestMapping("/api/v1/transfer-request-attempts/{attemptId}")
public final class TransferRequestLifecycleController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final Set<String> WITHDRAWAL_FIELDS =
      Set.of("expectedVersion", "reasonCode", "occurredAt");
  private static final java.util.regex.Pattern REASON_CODE =
      java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

  private final TransferRequestLifecycleCommands commands;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;

  public TransferRequestLifecycleController(
      TransferRequestLifecycleCommands commands,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper) {
    this.commands = commands;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/withdrawal")
  public ResponseEntity<RequestLifecycleRepresentation> withdraw(
      @PathVariable String attemptId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    WithdrawalContent content = withdrawalContent(body);
    TransferRequestLifecycleCommandResult result =
        commands.withdraw(
            executionContext(actor, scope, organizationId, facilityId),
            parseAttemptId(attemptId),
            content.reasonCode(),
            content.occurredAt(),
            content.expectedVersion(),
            idempotencyKey,
            RequestFingerprint.from(content.normalized()));
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) {
      headers.set("Idempotency-Replayed", "true");
    }
    return new ResponseEntity<>(
        RequestLifecycleRepresentation.from(result.attempt()), headers, HttpStatus.CREATED);
  }

  private WithdrawalContent withdrawalContent(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw invalid("A Transfer withdrawal body must be a JSON object.");
    }
    body.properties()
        .forEach(
            field -> {
              if (!WITHDRAWAL_FIELDS.contains(field.getKey())) {
                throw invalid("The Transfer withdrawal contains an unsupported field.");
              }
            });
    JsonNode versionNode = body.get("expectedVersion");
    if (versionNode == null
        || !versionNode.isIntegralNumber()
        || !versionNode.canConvertToLong()
        || versionNode.longValue() < 1) {
      throw invalid("A positive expected request version is required.");
    }
    String reasonCode = requiredText(body.get("reasonCode"));
    if (!REASON_CODE.matcher(reasonCode).matches()) {
      throw invalid("A structured withdrawal reason is required.");
    }
    Instant occurredAt;
    try {
      occurredAt = Instant.parse(requiredText(body.get("occurredAt")));
    } catch (RuntimeException malformed) {
      throw invalid("A valid withdrawal occurrence time is required.");
    }
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("expectedVersion", versionNode.longValue());
    normalized.put("reasonCode", reasonCode);
    normalized.put("occurredAt", occurredAt.toString());
    return new WithdrawalContent(versionNode.longValue(), reasonCode, occurredAt, normalized);
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

  private ExternalReference directoryReference(String type, String value) {
    try {
      return new ExternalReference(
          new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(value).toString());
    } catch (IllegalArgumentException malformed) {
      throw invalidScope();
    }
  }

  private static String requiredText(JsonNode value) {
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw invalid("A required withdrawal field is missing.");
    }
    return value.textValue();
  }

  private static InternalId parseAttemptId(String value) {
    try {
      return InternalId.parse(value);
    } catch (IllegalArgumentException malformed) {
      throw invalid("A valid request attempt identifier is required.");
    }
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException invalid(String detail) {
    return new PlatformProblemException(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSFER_REQUEST_INVALID, detail);
  }

  private static PlatformProblemException invalidScope() {
    return new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Withdrawal requires a validated Directory origin scope.");
  }

  private record WithdrawalContent(
      long expectedVersion, String reasonCode, Instant occurredAt, ObjectNode normalized) {}

  public record RequestLifecycleRepresentation(
      String requestAttemptId, String status, long requestVersion) {

    static RequestLifecycleRepresentation from(TransferRequestAttempt attempt) {
      return new RequestLifecycleRepresentation(
          attempt.id().toString(), attempt.status().name(), attempt.version());
    }
  }
}
