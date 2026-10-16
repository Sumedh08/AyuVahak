package org.ayuvahak.transport.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.ActiveInboundPartnerProfileLookupPort;
import org.ayuvahak.platform.exchange.application.PartnerTrustLookupUnavailableException;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeDeniedException;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transport.application.TransportCancellationCommands;
import org.ayuvahak.transport.application.TransportDestinationChangeCommands;
import org.ayuvahak.transport.domain.TransportCancellationOperation;
import org.ayuvahak.transport.domain.TransportDestinationChangeOperation;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Named provider outcome ingress bound to the active partner profile and scoped owner grants. */
@RestController
@RequestMapping("/api/v1/transport-provider")
public final class TransportProviderOutcomeController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final int HARD_MAX_BODY_BYTES = 256 * 1024;
  private static final Set<String> OUTCOME_FIELDS = Set.of("outcome", "occurredAt");

  private final ActiveInboundPartnerProfileLookupPort profiles;
  private final TransportCancellationCommands cancellations;
  private final TransportDestinationChangeCommands destinationChanges;
  private final ExecutionContextFactory contexts;
  private final ObjectMapper mapper;
  private final AuditWriter audit;
  private final Clock clock;

  public TransportProviderOutcomeController(
      ActiveInboundPartnerProfileLookupPort profiles,
      TransportCancellationCommands cancellations,
      TransportDestinationChangeCommands destinationChanges,
      ExecutionContextFactory contexts,
      ObjectMapper mapper,
      AuditWriter audit,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.profiles = profiles;
    this.cancellations = cancellations;
    this.destinationChanges = destinationChanges;
    this.contexts = contexts;
    this.mapper = mapper;
    this.audit = audit;
    this.clock = clock;
  }

  @PostMapping(
      path = "/cancellation-operations/{operationId}/outcome",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProviderOutcomeRepresentation> cancellationOutcome(
      @PathVariable String operationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "X-External-Message-ID", required = false) String messageId,
      HttpServletRequest request) {
    InternalId parsedOperationId = parseId(operationId);
    PartnerProfileVersion profile = requireProfile(actor, parsedOperationId);
    requireMessageId(messageId);
    JsonNode body = read(request, profile);
    TransportCancellationOperation.Outcome outcome = cancellationOutcome(body);
    Instant occurredAt = instant(body);
    ExecutionContext context = context(actor, scope, organizationId, facilityId, parsedOperationId);
    var result =
        cancellations.resolveFromPartner(
            context,
            parsedOperationId,
            new TransportCancellationCommands.ResolutionInput(
                outcome, namespace(profile), messageId, occurredAt),
            messageId,
            RequestFingerprint.from(body),
            profile.configuration().partnerKey().value());
    return response(
        result.operation().id().toString(),
        result.operation().state().name(),
        result.operation().outcome().map(Enum::name).orElse(null),
        result.commandReplayed());
  }

  @PostMapping(
      path = "/destination-change-operations/{operationId}/outcome",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProviderOutcomeRepresentation> destinationChangeOutcome(
      @PathVariable String operationId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "X-External-Message-ID", required = false) String messageId,
      HttpServletRequest request) {
    InternalId parsedOperationId = parseId(operationId);
    PartnerProfileVersion profile = requireProfile(actor, parsedOperationId);
    requireMessageId(messageId);
    JsonNode body = read(request, profile);
    TransportDestinationChangeOperation.Outcome outcome = destinationChangeOutcome(body);
    Instant occurredAt = instant(body);
    ExecutionContext context = context(actor, scope, organizationId, facilityId, parsedOperationId);
    var result =
        destinationChanges.resolveFromPartner(
            context,
            parsedOperationId,
            outcome,
            namespace(profile),
            messageId,
            occurredAt,
            RequestFingerprint.from(body),
            profile.configuration().partnerKey().value());
    return response(
        result.operation().id().toString(),
        result.operation().state().name(),
        result.operation().outcome().map(Enum::name).orElse(null),
        result.commandReplayed());
  }

  private PartnerProfileVersion requireProfile(ActorReference actor, InternalId operationId) {
    if (actor == null || actor.kind() != ActorKind.SERVICE) {
      recordDenied(actor, operationId, "PROVIDER_IDENTITY_REQUIRED");
      throw problem(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified provider service identity is required.");
    }
    try {
      Optional<PartnerProfileVersion> profile =
          profiles.findActive(actor, AllowedOperation.transportProviderOutcomes());
      if (profile.isEmpty()) {
        recordDenied(actor, operationId, "PARTNER_PROFILE_DENIED");
        throw denied();
      }
      return profile.orElseThrow();
    } catch (PartnerTrustLookupUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSPORT_UNAVAILABLE,
          "Provider trust could not be checked; no outcome was accepted.");
    }
  }

  private JsonNode read(HttpServletRequest request, PartnerProfileVersion profile) {
    int maximum =
        Math.min(profile.configuration().limits().maxResponseBytes(), HARD_MAX_BODY_BYTES);
    if (request.getContentLengthLong() > maximum) {
      throw invalid("Provider outcome exceeds the configured request limit.");
    }
    byte[] bytes = readBounded(request, maximum);
    JsonNode body;
    try {
      body = mapper.readTree(bytes);
    } catch (JacksonException malformed) {
      throw invalid("Provider outcome is not valid bounded JSON.");
    }
    if (body == null
        || !body.isObject()
        || body.properties().stream().anyMatch(field -> !OUTCOME_FIELDS.contains(field.getKey()))) {
      throw invalid("Provider outcome contains unsupported fields.");
    }
    if (maximumDepth(body, 0) > Math.min(profile.configuration().limits().maxNestingDepth(), 12)) {
      throw invalid("Provider outcome exceeds the configured nesting limit.");
    }
    return body;
  }

  private ExecutionContext context(
      ActorReference actor,
      String scope,
      String organizationId,
      String facilityId,
      InternalId operationId) {
    if (!"FACILITY".equals(scope) || organizationId == null || facilityId == null) {
      recordDenied(actor, operationId, "EXECUTION_SCOPE_INVALID");
      throw invalidScope();
    }
    try {
      return contexts.forIntegrationRequest(
          actor,
          RequestedExecutionScope.facility(
              directoryReference("Organization", organizationId),
              directoryReference("Facility", facilityId)),
          correlationId());
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      recordDenied(actor, operationId, "EXECUTION_SCOPE_INVALID");
      throw invalidScope();
    }
  }

  private void recordDenied(ActorReference actor, InternalId operationId, String reason) {
    try {
      audit.recordNonMutating(
          new AuditEvidence(
              AuditActionKind.SECURITY_OUTCOME,
              Optional.ofNullable(actor),
              Optional.empty(),
              "TRANSPORT_PROVIDER_OUTCOME_DENIED",
              "TransportProviderOperation",
              Optional.of(operationId),
              clock.instant(),
              AuditOutcome.DENIED,
              Optional.of(reason),
              authenticationEvidence(actor),
              AuditAuthorizationOutcome.NOT_EVALUATED,
              correlationId()));
    } catch (AuditPersistenceUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUDIT_UNAVAILABLE,
          "The provider outcome remains denied because its audit is unavailable.");
    }
  }

  private static AuditAuthenticationEvidence authenticationEvidence(ActorReference actor) {
    if (actor == null) return AuditAuthenticationEvidence.INVALID_CREDENTIAL;
    return actor.kind() == ActorKind.HUMAN
        ? AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN
        : AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
  }

  private TransportCancellationOperation.Outcome cancellationOutcome(JsonNode body) {
    try {
      return TransportCancellationOperation.Outcome.valueOf(requiredText(body, "outcome", 64));
    } catch (RuntimeException malformed) {
      throw invalid("Provider cancellation outcome is unsupported.");
    }
  }

  private TransportDestinationChangeOperation.Outcome destinationChangeOutcome(JsonNode body) {
    try {
      return TransportDestinationChangeOperation.Outcome.valueOf(requiredText(body, "outcome", 64));
    } catch (RuntimeException malformed) {
      throw invalid("Provider destination-change outcome is unsupported.");
    }
  }

  private Instant instant(JsonNode body) {
    try {
      return Instant.parse(requiredText(body, "occurredAt", 64));
    } catch (RuntimeException malformed) {
      throw invalid("Provider outcome source time is invalid.");
    }
  }

  private String requiredText(JsonNode body, String field, int maximumLength) {
    JsonNode value = body.get(field);
    if (value == null
        || !value.isString()
        || value.asString().isBlank()
        || value.asString().length() > maximumLength
        || !value.asString().equals(value.asString().strip())) {
      throw invalid("Provider outcome is missing a bounded " + field + ".");
    }
    return value.asString();
  }

  private static byte[] readBounded(HttpServletRequest request, int maximum) {
    try (var input = request.getInputStream();
        var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        if (count == 0) continue;
        if (count > maximum - output.size()) {
          throw invalid("Provider outcome exceeds the configured request limit.");
        }
        output.write(buffer, 0, count);
      }
      if (output.size() == 0) throw invalid("Provider outcome body is required.");
      return output.toByteArray();
    } catch (IOException failure) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "Provider outcome could not be read safely.");
    }
  }

  private static int maximumDepth(JsonNode node, int depth) {
    if (node == null || node.isValueNode()) return depth;
    int maximum = depth;
    for (JsonNode child : node) maximum = Math.max(maximum, maximumDepth(child, depth + 1));
    return maximum;
  }

  private static void requireMessageId(String value) {
    if (value == null
        || value.isEmpty()
        || value.length() > 128
        || value.codePoints().anyMatch(codePoint -> codePoint < 0x21 || codePoint > 0x7e)) {
      throw invalid("A bounded external message identity is required.");
    }
  }

  private static InternalId parseId(String value) {
    try {
      return InternalId.parse(value);
    } catch (RuntimeException malformed) {
      throw invalid("A valid Transport operation identifier is required.");
    }
  }

  private static ExternalReference directoryReference(String type, String value) {
    try {
      return new ExternalReference(
          new IssuerId(DIRECTORY_ISSUER), type, InternalId.parse(value).toString());
    } catch (RuntimeException malformed) {
      throw invalidScope();
    }
  }

  private static String namespace(PartnerProfileVersion profile) {
    return "urn:ayuvahak:partner:" + profile.configuration().partnerKey().value();
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static ResponseEntity<ProviderOutcomeRepresentation> response(
      String operationId, String state, String outcome, boolean replayed) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (replayed) headers.set("Idempotency-Replayed", "true");
    return new ResponseEntity<>(
        new ProviderOutcomeRepresentation(operationId, state, outcome),
        headers,
        HttpStatus.CREATED);
  }

  private static PlatformProblemException denied() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated partner is not active for this Transport outcome operation.");
  }

  private static PlatformProblemException invalidScope() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Provider outcome requires an authorized validated origin Facility scope.");
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  public record ProviderOutcomeRepresentation(String operationId, String state, String outcome) {}
}
