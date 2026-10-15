package org.ayuvahak.platform.exchange.adapter.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.PartnerProfileCommandResult;
import org.ayuvahak.platform.exchange.application.PartnerTrustAdministration;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy;
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
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Protected HTTP administration boundary for platform-owned exchange partner trust. */
@Validated
@RestController
@RequestMapping("/api/v1/platform/exchange-partners")
public class PlatformPartnerTrustController {

  public static final String EXECUTION_SCOPE_HEADER = "X-AyuVahak-Execution-Scope";

  private final PartnerTrustAdministration administration;
  private final ExecutionContextFactory contextFactory;
  private final ObjectMapper objectMapper;
  private final AuthenticationProperties authenticationProperties;

  public PlatformPartnerTrustController(
      PartnerTrustAdministration administration,
      ExecutionContextFactory contextFactory,
      ObjectMapper objectMapper,
      AuthenticationProperties authenticationProperties) {
    this.administration = administration;
    this.contextFactory = contextFactory;
    this.objectMapper = objectMapper;
    this.authenticationProperties = authenticationProperties;
  }

  @PostMapping
  public ResponseEntity<PartnerProfileRepresentation> create(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreatePartnerProfileRequest request) {
    PartnerTrustConfiguration configuration =
        request
            .configuration()
            .toConfiguration(
                PartnerTrustControllerKey.of(request.partnerKey()), authenticationProperties);
    PartnerProfileCommandResult result =
        administration.create(
            context(actor, requestedScope),
            configuration,
            request.reasonCode(),
            idempotencyKey,
            fingerprint("CREATE", request));
    return response(result, request.partnerKey());
  }

  @PostMapping("/{partnerKey}/versions")
  public ResponseEntity<PartnerProfileRepresentation> createVersion(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String partnerKey,
      @Valid @RequestBody CreatePartnerVersionRequest request) {
    PartnerKey key = key(partnerKey);
    PartnerTrustConfiguration configuration =
        request.configuration().toConfiguration(key, authenticationProperties);
    PartnerProfileCommandResult result =
        administration.createVersion(
            context(actor, requestedScope),
            key,
            request.expectedCurrentVersion(),
            configuration,
            request.reasonCode(),
            idempotencyKey,
            fingerprint("CREATE_VERSION", key.value(), request));
    return response(result, key.value());
  }

  @PostMapping("/{partnerKey}/versions/{version}/activate")
  public ResponseEntity<PartnerProfileRepresentation> activate(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String partnerKey,
      @PathVariable int version,
      @Valid @RequestBody PartnerReasonRequest request) {
    PartnerKey key = key(partnerKey);
    PartnerProfileCommandResult result =
        administration.activate(
            context(actor, requestedScope),
            key,
            version,
            request.reasonCode(),
            idempotencyKey,
            fingerprint("ACTIVATE", key.value(), version, request.reasonCode()));
    return response(result, key.value());
  }

  @PostMapping("/{partnerKey}/suspend")
  public ResponseEntity<PartnerProfileRepresentation> suspend(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String partnerKey,
      @Valid @RequestBody PartnerReasonRequest request) {
    PartnerKey key = key(partnerKey);
    PartnerProfileCommandResult result =
        administration.suspend(
            context(actor, requestedScope),
            key,
            request.reasonCode(),
            idempotencyKey,
            fingerprint("SUSPEND", key.value(), request.reasonCode()));
    return response(result, key.value());
  }

  @PostMapping("/{partnerKey}/revoke")
  public ResponseEntity<PartnerProfileRepresentation> revoke(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String partnerKey,
      @Valid @RequestBody PartnerReasonRequest request) {
    PartnerKey key = key(partnerKey);
    PartnerProfileCommandResult result =
        administration.revoke(
            context(actor, requestedScope),
            key,
            request.reasonCode(),
            idempotencyKey,
            fingerprint("REVOKE", key.value(), request.reasonCode()));
    return response(result, key.value());
  }

  @GetMapping("/{partnerKey}/versions")
  public PartnerProfileCollection versions(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = EXECUTION_SCOPE_HEADER, required = false) String requestedScope,
      @PathVariable String partnerKey) {
    PartnerKey key = key(partnerKey);
    return new PartnerProfileCollection(
        administration.versions(context(actor, requestedScope), key).stream()
            .map(PartnerProfileRepresentation::from)
            .toList());
  }

  private ResponseEntity<PartnerProfileRepresentation> response(
      PartnerProfileCommandResult result, String partnerKey) {
    URI location =
        URI.create(
            "/api/v1/platform/exchange-partners/"
                + partnerKey
                + "/versions/"
                + result.profile().version());
    return ResponseEntity.status(result.status())
        .location(location)
        .body(PartnerProfileRepresentation.from(result.profile()));
  }

  private ExecutionContext context(ActorReference actor, String requestedScope) {
    if (!"PLATFORM".equals(requestedScope)) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "An explicit platform execution scope is required.");
    }
    CorrelationId correlationId = correlationId();
    if (actor.kind() == ActorKind.HUMAN) {
      return contextFactory.forInteractiveRequest(
          actor, RequestedExecutionScope.platform(), correlationId);
    }
    return contextFactory.forIntegrationRequest(
        actor, RequestedExecutionScope.platform(), correlationId);
  }

  private RequestFingerprint fingerprint(String action, CreatePartnerProfileRequest request) {
    return RequestFingerprint.from(normalizedConfiguration(action, request, request.reasonCode()));
  }

  private RequestFingerprint fingerprint(
      String action, String partnerKey, CreatePartnerVersionRequest request) {
    ObjectNode normalized =
        normalizedConfiguration(action, request.configuration(), request.reasonCode());
    normalized.put("partnerKey", partnerKey);
    normalized.put("expectedCurrentVersion", request.expectedCurrentVersion());
    return RequestFingerprint.from(normalized);
  }

  private RequestFingerprint fingerprint(
      String action, String partnerKey, int version, String reasonCode) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("action", action);
    normalized.put("partnerKey", partnerKey);
    normalized.put("version", version);
    normalized.put("reasonCode", reasonCode);
    return RequestFingerprint.from(normalized);
  }

  private RequestFingerprint fingerprint(String action, String partnerKey, String reasonCode) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("action", action);
    normalized.put("partnerKey", partnerKey);
    normalized.put("reasonCode", reasonCode);
    return RequestFingerprint.from(normalized);
  }

  private ObjectNode normalizedConfiguration(
      String action, PartnerConfigurationRequest request, String reasonCode) {
    ObjectNode normalized = objectMapper.createObjectNode();
    normalized.put("action", action);
    normalized.put("reasonCode", reasonCode);
    normalized.put("organizationIssuer", request.organization().issuer());
    normalized.put("organizationIdentifier", request.organization().identifier());
    normalized.put("endpointOrigin", request.endpointOrigin());
    normalized.put("basePath", request.basePath());
    normalized.put("authenticationMethod", request.authentication().method());
    normalized.put("credentialReference", request.authentication().credentialReference());
    normalized.put(
        "tokenSigningKeysReference", request.authentication().tokenSigningKeysReference());
    normalized.put("tokenOrigin", request.authentication().tokenOrigin());
    normalized.put("tokenPath", request.authentication().tokenPath());
    normalized.put("oauthIssuer", request.authentication().issuer());
    normalized.put("oauthAudience", request.authentication().audience());
    normalized.put("oauthClientId", request.authentication().clientId());
    normalized.put(
        "inboundPrincipalIssuer",
        request.inboundPrincipal() == null ? null : request.inboundPrincipal().issuer());
    normalized.put(
        "inboundPrincipalClientId",
        request.inboundPrincipal() == null ? null : request.inboundPrincipal().clientId());
    normalized.put("tlsPolicy", request.tlsPolicy());
    normalized.put("tlsTrustReference", request.tlsTrustReference());
    normalized.put("connectTimeoutMillis", request.limits().connectTimeoutMillis());
    normalized.put("responseTimeoutMillis", request.limits().responseTimeoutMillis());
    normalized.put("operationTimeoutMillis", request.limits().operationTimeoutMillis());
    normalized.put("maxResponseBytes", request.limits().maxResponseBytes());
    normalized.put("maxDecompressedBytes", request.limits().maxDecompressedBytes());
    normalized.put("maxPages", request.limits().maxPages());
    normalized.put("maxResources", request.limits().maxResources());
    normalized.put("maxNestingDepth", request.limits().maxNestingDepth());
    normalized.put("maxAttempts", request.limits().maxAttempts());
    normalized.put("retryClass", request.retryClass());
    ArrayNode operations = normalized.putArray("allowedOperations");
    request.allowedOperations().stream()
        .sorted(
            java.util.Comparator.comparing(OperationRequest::ownerContext)
                .thenComparing(OperationRequest::operationCode)
                .thenComparing(OperationRequest::direction)
                .thenComparing(OperationRequest::profile))
        .forEach(
            operation -> {
              ObjectNode item = operations.addObject();
              item.put("ownerContext", operation.ownerContext());
              item.put("operationCode", operation.operationCode());
              item.put("direction", operation.direction());
              item.put("protocol", operation.protocol());
              item.put("profile", operation.profile());
            });
    return normalized;
  }

  private static PartnerKey key(String partnerKey) {
    try {
      return new PartnerKey(partnerKey);
    } catch (IllegalArgumentException exception) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "The partner key is invalid.");
    }
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  public record CreatePartnerProfileRequest(
      @NotBlank String partnerKey,
      @Valid @NotNull OrganizationReferenceRequest organization,
      @NotBlank String endpointOrigin,
      @NotBlank String basePath,
      @Valid @NotNull AuthenticationRequest authentication,
      @NotBlank String tlsPolicy,
      String tlsTrustReference,
      @Valid @NotNull LimitsRequest limits,
      @NotBlank String retryClass,
      @NotEmpty List<@Valid OperationRequest> allowedOperations,
      @Valid InboundPrincipalRequest inboundPrincipal,
      @NotBlank String reasonCode) {

    PartnerTrustConfiguration toConfiguration(AuthenticationProperties authenticationProperties) {
      return configuration()
          .toConfiguration(PartnerTrustControllerKey.of(partnerKey), authenticationProperties);
    }

    PartnerConfigurationRequest configuration() {
      return new PartnerConfigurationRequest(
          organization,
          endpointOrigin,
          basePath,
          authentication,
          tlsPolicy,
          tlsTrustReference,
          limits,
          retryClass,
          allowedOperations,
          inboundPrincipal);
    }
  }

  public record CreatePartnerVersionRequest(
      @Min(1) int expectedCurrentVersion,
      @Valid @NotNull PartnerConfigurationRequest configuration,
      @NotBlank String reasonCode) {}

  public record PartnerConfigurationRequest(
      @Valid @NotNull OrganizationReferenceRequest organization,
      @NotBlank String endpointOrigin,
      @NotBlank String basePath,
      @Valid @NotNull AuthenticationRequest authentication,
      @NotBlank String tlsPolicy,
      String tlsTrustReference,
      @Valid @NotNull LimitsRequest limits,
      @NotBlank String retryClass,
      @NotEmpty List<@Valid OperationRequest> allowedOperations,
      @Valid InboundPrincipalRequest inboundPrincipal) {

    PartnerTrustConfiguration toConfiguration(
        PartnerKey partnerKey, AuthenticationProperties authenticationProperties) {
      try {
        if (inboundPrincipal != null
            && (!authenticationProperties.issuer().equals(inboundPrincipal.issuer())
                || !authenticationProperties
                    .serviceClientIds()
                    .contains(inboundPrincipal.clientId()))) {
          throw new IllegalArgumentException(
              "inbound principal is not a configured service identity");
        }
        return new PartnerTrustConfiguration(
            partnerKey,
            new ExternalReference(
                new IssuerId(organization.issuer()), "Organization", organization.identifier()),
            endpointOrigin,
            basePath,
            authentication.toDomain(),
            TlsPolicy.valueOf(tlsPolicy),
            Optional.ofNullable(tlsTrustReference),
            limits.toDomain(),
            RetryClass.valueOf(retryClass),
            allowedOperations.stream().map(OperationRequest::toDomain).toList(),
            Optional.ofNullable(inboundPrincipal).map(InboundPrincipalRequest::toDomain));
      } catch (IllegalArgumentException exception) {
        throw new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            PlatformErrorCode.VALIDATION_FAILED,
            "The partner trust configuration is invalid.");
      }
    }
  }

  public record OrganizationReferenceRequest(
      @NotBlank String issuer, @NotBlank String identifier) {}

  public record InboundPrincipalRequest(@NotBlank String issuer, @NotBlank String clientId) {

    ActorReference toDomain() {
      return ActorReference.service(new IssuerId(issuer), clientId);
    }
  }

  public record AuthenticationRequest(
      @NotBlank String method,
      String credentialReference,
      String tokenSigningKeysReference,
      String tokenOrigin,
      String tokenPath,
      String issuer,
      String audience,
      String clientId) {

    Authentication toDomain() {
      try {
        return new Authentication(
            AuthenticationMethod.valueOf(method),
            Optional.ofNullable(credentialReference),
            Optional.ofNullable(tokenSigningKeysReference),
            Optional.ofNullable(tokenOrigin),
            Optional.ofNullable(tokenPath),
            Optional.ofNullable(issuer),
            Optional.ofNullable(audience),
            Optional.ofNullable(clientId));
      } catch (IllegalArgumentException exception) {
        throw new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            PlatformErrorCode.VALIDATION_FAILED,
            "The partner authentication settings are invalid.");
      }
    }
  }

  public record LimitsRequest(
      @Min(100) @Max(120_000) int connectTimeoutMillis,
      @Min(100) @Max(120_000) int responseTimeoutMillis,
      @Min(100) @Max(300_000) int operationTimeoutMillis,
      @Min(1) @Max(20_000_000) int maxResponseBytes,
      @Min(1) @Max(40_000_000) int maxDecompressedBytes,
      @Min(1) @Max(100) int maxPages,
      @Min(1) @Max(10_000) int maxResources,
      @Min(1) @Max(64) int maxNestingDepth,
      @Min(1) @Max(10) int maxAttempts) {

    Limits toDomain() {
      return new Limits(
          connectTimeoutMillis,
          responseTimeoutMillis,
          operationTimeoutMillis,
          maxResponseBytes,
          maxDecompressedBytes,
          maxPages,
          maxResources,
          maxNestingDepth,
          maxAttempts);
    }
  }

  public record OperationRequest(
      @NotBlank String ownerContext,
      @NotBlank String operationCode,
      @NotBlank String direction,
      @NotBlank String protocol,
      @NotBlank String profile) {

    AllowedOperation toDomain() {
      return new AllowedOperation(
          OwnerContext.valueOf(ownerContext),
          operationCode,
          Direction.valueOf(direction),
          Protocol.valueOf(protocol),
          profile);
    }
  }

  public record PartnerReasonRequest(@NotBlank String reasonCode) {}

  public record PartnerProfileCollection(List<PartnerProfileRepresentation> items) {
    public PartnerProfileCollection {
      items = List.copyOf(items);
    }
  }

  public record PartnerProfileRepresentation(
      String profileVersionId,
      String partnerKey,
      int version,
      String state,
      String organizationIssuer,
      String organizationIdentifier,
      String endpointOrigin,
      String basePath,
      String authenticationMethod,
      boolean credentialConfigured,
      boolean tokenSigningKeysConfigured,
      String oauthAudience,
      String oauthClientId,
      String inboundPrincipalIssuer,
      String inboundPrincipalClientId,
      String tlsPolicy,
      boolean tlsTrustConfigured,
      int connectTimeoutMillis,
      int responseTimeoutMillis,
      int operationTimeoutMillis,
      int maxResponseBytes,
      int maxDecompressedBytes,
      int maxPages,
      int maxResources,
      int maxNestingDepth,
      int maxAttempts,
      String retryClass,
      List<OperationRequest> allowedOperations,
      java.time.Instant createdAt,
      java.time.Instant updatedAt) {

    static PartnerProfileRepresentation from(PartnerProfileVersion profile) {
      PartnerTrustConfiguration configuration = profile.configuration();
      Authentication authentication = configuration.authentication();
      Limits limits = configuration.limits();
      return new PartnerProfileRepresentation(
          profile.id().toString(),
          configuration.partnerKey().value(),
          profile.version(),
          profile.state().name(),
          configuration.organization().issuer().value(),
          configuration.organization().value(),
          configuration.endpointOrigin(),
          configuration.basePath(),
          authentication.method().name(),
          authentication.credentialReference().isPresent(),
          authentication.tokenSigningKeysReference().isPresent(),
          authentication.audience().orElse(null),
          authentication.clientId().orElse(null),
          configuration
              .inboundPrincipal()
              .map(value -> value.identity().issuer().value())
              .orElse(null),
          configuration.inboundPrincipal().map(value -> value.identity().value()).orElse(null),
          configuration.tlsPolicy().name(),
          configuration.tlsTrustReference().isPresent(),
          limits.connectTimeoutMillis(),
          limits.responseTimeoutMillis(),
          limits.operationTimeoutMillis(),
          limits.maxResponseBytes(),
          limits.maxDecompressedBytes(),
          limits.maxPages(),
          limits.maxResources(),
          limits.maxNestingDepth(),
          limits.maxAttempts(),
          configuration.retryClass().name(),
          configuration.allowedOperations().stream()
              .map(
                  operation ->
                      new OperationRequest(
                          operation.ownerContext().name(),
                          operation.operationCode(),
                          operation.direction().name(),
                          operation.protocol().name(),
                          operation.profile()))
              .toList(),
          profile.createdAt(),
          profile.updatedAt());
    }
  }

  private ObjectNode normalizedConfiguration(
      String action, CreatePartnerProfileRequest request, String reasonCode) {
    ObjectNode normalized = normalizedConfiguration(action, request.configuration(), reasonCode);
    normalized.put("partnerKey", PartnerTrustControllerKey.of(request.partnerKey()).value());
    return normalized;
  }

  private static final class PartnerTrustControllerKey {

    private PartnerTrustControllerKey() {}

    static PartnerKey of(String value) {
      try {
        return new PartnerKey(value);
      } catch (IllegalArgumentException exception) {
        throw new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            PlatformErrorCode.VALIDATION_FAILED,
            "The partner key is invalid.");
      }
    }
  }
}
