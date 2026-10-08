package org.ayuvahak.platform.authorization.adapter.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.authorization.application.AccessGrantAdministration;
import org.ayuvahak.platform.authorization.application.GrantMutationResult;
import org.ayuvahak.platform.authorization.application.GrantPage;
import org.ayuvahak.platform.authorization.application.GrantPrincipal;
import org.ayuvahak.platform.authorization.domain.AccessGrant;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Protected HTTP boundary for the three platform-level authorization authorities. */
@Validated
@RestController
@RequestMapping("/api/v1/platform/access-grants")
public class PlatformAccessGrantController {

  public static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";
  private final AccessGrantAdministration administration;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;

  public PlatformAccessGrantController(
      AccessGrantAdministration administration,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper) {
    this.administration = administration;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
  }

  @PostMapping
  public ResponseEntity<GrantCreated> create(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateGrantRequest request) {
    ExecutionContext context = context(actor, requestedScope);
    GrantPrincipal principal = request.principal().toGrantPrincipal();
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("kind", principal.kind().name());
    normalized.put("issuer", principal.issuer().value());
    normalized.put("identifier", principal.identifier());
    normalized.put("authority", request.authority());
    normalized.put("scope", "PLATFORM");
    normalized.put("reasonCode", request.reasonCode());
    GrantMutationResult result =
        administration.create(
            context,
            principal,
            request.authority(),
            request.reasonCode(),
            idempotencyKey,
            RequestFingerprint.from(normalized));
    return ResponseEntity.status(result.status())
        .location(URI.create("/api/v1/platform/access-grants/" + result.grantId()))
        .body(new GrantCreated(result.grantId().toString()));
  }

  @GetMapping
  public GrantCollection list(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor) {
    GrantPage page =
        administration.list(context(actor, requestedScope), Optional.ofNullable(cursor), limit);
    return new GrantCollection(
        page.grants().stream().map(GrantRepresentation::from).toList(), page.nextCursor());
  }

  @DeleteMapping("/{grantId}")
  public ResponseEntity<Void> revoke(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String grantId,
      @Valid @RequestBody RevokeGrantRequest request) {
    ExecutionContext context = context(actor, requestedScope);
    org.ayuvahak.platform.core.InternalId internalId;
    try {
      internalId = org.ayuvahak.platform.core.InternalId.parse(grantId);
    } catch (IllegalArgumentException exception) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND,
          PlatformErrorCode.GRANT_NOT_FOUND,
          "The requested grant was not found.");
    }
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("grantId", internalId.toString());
    normalized.put("reasonCode", request.reasonCode());
    GrantMutationResult result =
        administration.revoke(
            context,
            internalId,
            request.reasonCode(),
            idempotencyKey,
            RequestFingerprint.from(normalized));
    return ResponseEntity.status(result.status()).build();
  }

  private ExecutionContext context(ActorReference actor, String requestedScope) {
    CorrelationId correlationId = correlationId();
    if (!"PLATFORM".equals(requestedScope)) {
      administration.recordInvalidScope(actor, correlationId);
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

  public record CreateGrantRequest(
      @Valid @NotNull PrincipalRequest principal,
      @NotBlank String authority,
      @NotBlank String reasonCode) {}

  public record PrincipalRequest(
      @NotBlank String kind, @NotBlank String issuer, String subject, String clientId) {
    GrantPrincipal toGrantPrincipal() {
      try {
        ActorKind actorKind = ActorKind.valueOf(kind);
        String identifier =
            switch (actorKind) {
              case HUMAN -> {
                if (clientId != null || subject == null) {
                  throw new IllegalArgumentException("invalid human principal shape");
                }
                yield subject;
              }
              case SERVICE -> {
                if (subject != null || clientId == null) {
                  throw new IllegalArgumentException("invalid service principal shape");
                }
                yield clientId;
              }
            };
        return new GrantPrincipal(actorKind, new IssuerId(issuer), identifier);
      } catch (IllegalArgumentException exception) {
        throw new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            PlatformErrorCode.VALIDATION_FAILED,
            "The principal reference is invalid.");
      }
    }
  }

  public record RevokeGrantRequest(@NotBlank String reasonCode) {}

  public record GrantCreated(String grantId) {}

  public record GrantCollection(List<GrantRepresentation> items, Optional<String> nextCursor) {
    public GrantCollection {
      items = List.copyOf(items);
    }
  }

  public record GrantRepresentation(
      String grantId,
      String kind,
      String issuer,
      String subject,
      String clientId,
      String authority,
      String scope,
      String status,
      java.time.Instant createdAt,
      Optional<java.time.Instant> revokedAt) {
    static GrantRepresentation from(AccessGrant grant) {
      boolean human = grant.principal().kind() == ActorKind.HUMAN;
      return new GrantRepresentation(
          grant.id().toString(),
          grant.principal().kind().name(),
          grant.principal().identity().issuer().value(),
          human ? grant.principal().identity().value() : null,
          human ? null : grant.principal().identity().value(),
          grant.authorityCode(),
          "PLATFORM",
          grant.status().name(),
          grant.createdAt(),
          grant.revokedAt());
    }
  }
}
