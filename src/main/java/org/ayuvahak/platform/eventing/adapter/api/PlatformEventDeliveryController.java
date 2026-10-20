package org.ayuvahak.platform.eventing.adapter.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.eventing.application.EventDeliveryOperations;
import org.ayuvahak.platform.eventing.application.EventDeliveryPage;
import org.ayuvahak.platform.eventing.application.EventDeliverySummary;
import org.ayuvahak.platform.eventing.application.PlatformOutboxRetentionManifest;
import org.ayuvahak.platform.eventing.application.RetentionHoldPurpose;
import org.ayuvahak.platform.eventing.application.RetentionHoldReleaseReason;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Restricted platform API for inspecting and replaying internal event deliveries. */
@Validated
@RestController
@RequestMapping("/api/v1/platform/event-deliveries")
public class PlatformEventDeliveryController {

  public static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private final EventDeliveryOperations operations;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;

  public PlatformEventDeliveryController(
      EventDeliveryOperations operations,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper) {
    this.operations = operations;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
  }

  @GetMapping
  public DeliveryCollection list(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(required = false) String cursor) {
    EventDeliveryPage page =
        operations.list(context(actor, requestedScope), Optional.ofNullable(cursor), limit);
    return new DeliveryCollection(
        page.items().stream().map(DeliveryRepresentation::from).toList(), page.nextCursor());
  }

  @PostMapping("/{eventId}/replay")
  public ResponseEntity<ReplayAccepted> replay(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String eventId) {
    InternalId id;
    try {
      id = InternalId.parse(eventId);
    } catch (IllegalArgumentException invalid) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.EVENT_NOT_FOUND,
          "The requested event delivery was not found.");
    }
    var fingerprintDocument = objectMapper.createObjectNode();
    fingerprintDocument.put("eventId", id.toString());
    var result =
        operations.replay(
            context(actor, requestedScope),
            id,
            idempotencyKey,
            RequestFingerprint.from(fingerprintDocument));
    return ResponseEntity.accepted()
        .body(new ReplayAccepted(result.eventId().toString(), result.deliveryGeneration()));
  }

  @PostMapping("/{eventId}/retention-holds")
  public ResponseEntity<RetentionHoldAccepted> placeRetentionHold(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @PathVariable String eventId,
      @RequestBody JsonNode body) {
    InternalId id = parseEventId(eventId);
    RetentionHoldRequest request = readRetentionHoldRequest(body);
    var result =
        operations.placeRetentionHold(
            context(actor, requestedScope), id, request.purpose(), request.reviewBy());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new RetentionHoldAccepted(
                result.holdId().toString(),
                result.eventId().toString(),
                result.purpose().name(),
                result.reviewBy()));
  }

  @PostMapping("/{eventId}/retention-holds/{holdId}/release")
  public ResponseEntity<Void> releaseRetentionHold(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @PathVariable String eventId,
      @PathVariable String holdId,
      @RequestBody JsonNode body) {
    InternalId id = parseEventId(eventId);
    UUID parsedHoldId;
    try {
      parsedHoldId = UUID.fromString(holdId);
    } catch (IllegalArgumentException invalid) {
      throw validation("A valid retention hold id is required.");
    }
    RetentionHoldReleaseReason reason = readRetentionHoldReleaseReason(body);
    operations.releaseRetentionHold(context(actor, requestedScope), id, parsedHoldId, reason);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/retention-reapplication/export")
  public PlatformOutboxRetentionManifest exportRetentionManifest(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestBody JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.size() != 2
        || !body.has("restoreId")
        || !body.has("backupCompletedAt")
        || !body.get("restoreId").isTextual()
        || !body.get("backupCompletedAt").isTextual()) {
      throw validation("A restoreId and backupCompletedAt are required.");
    }
    try {
      return operations.exportRetentionManifest(
          context(actor, requestedScope),
          UUID.fromString(body.get("restoreId").stringValue()),
          Instant.parse(body.get("backupCompletedAt").stringValue()));
    } catch (IllegalArgumentException invalid) {
      throw validation("The retention manifest export request is invalid.");
    }
  }

  @PostMapping("/retention-reapplication")
  public ResponseEntity<Void> reapplyRetentionManifest(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestBody JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.size() != 5
        || !body.has("restoreId")
        || !body.has("backupCompletedAt")
        || !body.has("generatedAt")
        || !body.has("purgedEvents")
        || !body.has("holds")) {
      throw validation("A Platform retention manifest object is required.");
    }
    final PlatformOutboxRetentionManifest manifest;
    try {
      manifest = objectMapper.treeToValue(body, PlatformOutboxRetentionManifest.class);
    } catch (tools.jackson.core.JacksonException
        | IllegalArgumentException
        | NullPointerException malformed) {
      throw validation("The Platform retention manifest is malformed.");
    }
    operations.reapplyRetentionManifest(context(actor, requestedScope), manifest);
    return ResponseEntity.noContent().build();
  }

  private RetentionHoldRequest readRetentionHoldRequest(JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.size() != 2
        || !body.has("purposeCode")
        || !body.has("reviewBy")
        || !body.get("purposeCode").isString()
        || !body.get("reviewBy").isString()) {
      throw validation("A purposeCode and reviewBy are required for a retention hold.");
    }
    try {
      return new RetentionHoldRequest(
          RetentionHoldPurpose.valueOf(body.get("purposeCode").stringValue()),
          Instant.parse(body.get("reviewBy").stringValue()));
    } catch (IllegalArgumentException invalid) {
      throw validation("The retention hold purpose or review time is invalid.");
    }
  }

  private RetentionHoldReleaseReason readRetentionHoldReleaseReason(JsonNode body) {
    if (body == null
        || !body.isObject()
        || body.size() != 1
        || !body.has("reasonCode")
        || !body.get("reasonCode").isString()) {
      throw validation("A reasonCode is required to release a retention hold.");
    }
    try {
      return RetentionHoldReleaseReason.valueOf(body.get("reasonCode").stringValue());
    } catch (IllegalArgumentException invalid) {
      throw validation("The retention hold release reason is invalid.");
    }
  }

  private static PlatformProblemException validation(String detail) {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST, PlatformErrorCode.VALIDATION_FAILED, detail);
  }

  private InternalId parseEventId(String eventId) {
    try {
      return InternalId.parse(eventId);
    } catch (IllegalArgumentException invalid) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.EVENT_NOT_FOUND,
          "The requested event delivery was not found.");
    }
  }

  private ExecutionContext context(ActorReference actor, String requestedScope) {
    CorrelationId correlationId = correlationId();
    if (!"PLATFORM".equals(requestedScope)) {
      operations.recordInvalidScope(actor, correlationId);
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "An explicit platform execution scope is required.");
    }
    if (actor.kind() == ActorKind.HUMAN) {
      return contextFactory.forInteractiveRequest(
          actor, RequestedExecutionScope.platform(), correlationId);
    }
    return contextFactory.forIntegrationRequest(
        actor, RequestedExecutionScope.platform(), correlationId);
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  public record DeliveryCollection(
      List<DeliveryRepresentation> items, Optional<String> nextCursor) {
    public DeliveryCollection {
      items = List.copyOf(items);
    }
  }

  public record DeliveryRepresentation(
      String eventId,
      String eventType,
      String producerContext,
      Optional<String> aggregateRef,
      Optional<Long> aggregateSequence,
      int deliveryGeneration,
      String state,
      int attemptCount,
      java.time.Instant eventRecordedAt,
      java.time.Instant deliveryCreatedAt,
      java.time.Instant deliveryUpdatedAt) {
    static DeliveryRepresentation from(EventDeliverySummary summary) {
      return new DeliveryRepresentation(
          summary.eventId().toString(),
          summary.eventType(),
          summary.producerContext(),
          summary.aggregateRef(),
          summary.aggregateSequence(),
          summary.deliveryGeneration(),
          summary.state(),
          summary.attemptCount(),
          summary.eventRecordedAt(),
          summary.deliveryCreatedAt(),
          summary.deliveryUpdatedAt());
    }
  }

  public record ReplayAccepted(String eventId, int deliveryGeneration) {}

  private record RetentionHoldRequest(RetentionHoldPurpose purpose, Instant reviewBy) {}

  public record RetentionHoldAccepted(
      String holdId, String eventId, String purposeCode, Instant reviewBy) {}
}
