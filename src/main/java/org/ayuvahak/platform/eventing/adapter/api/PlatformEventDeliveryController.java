package org.ayuvahak.platform.eventing.adapter.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
}
