package org.ayuvahak.transport.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
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
import org.ayuvahak.platform.exchange.application.ActiveInboundPartnerProfileLookupPort;
import org.ayuvahak.platform.exchange.application.PartnerTrustLookupUnavailableException;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeDeniedException;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transport.application.TransportEvidenceCommandResult;
import org.ayuvahak.transport.application.TransportEvidenceCommands;
import org.ayuvahak.transport.domain.TransportRequest;
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

/** Named partner-authenticated ingress for allowlisted, scoped Transport evidence. */
@RestController
@RequestMapping("/api/v1/transport-provider/evidence/{transportRequestId}")
public final class TransportProviderEvidenceController {

  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String FINGERPRINT_NAMESPACE = "urn:ayuvahak:transport-provider-message";
  private static final int HARD_MAX_BODY_BYTES = 256 * 1024;
  private static final Set<String> STATUS_FIELDS =
      Set.of("observationId", "sourceSequence", "status", "occurredAt", "reasonCode");
  private static final Set<String> ASSIGNMENT_FIELDS =
      Set.of("observationId", "assignmentReference", "occurredAt");
  private static final Set<String> ETA_FIELDS =
      Set.of("observationId", "estimatedArrivalAt", "observedAt");

  private final ActiveInboundPartnerProfileLookupPort profiles;
  private final TransportEvidenceCommands evidence;
  private final ExecutionContextFactory contexts;
  private final ObjectMapper mapper;

  public TransportProviderEvidenceController(
      ActiveInboundPartnerProfileLookupPort profiles,
      TransportEvidenceCommands evidence,
      ExecutionContextFactory contexts,
      ObjectMapper mapper) {
    this.profiles = profiles;
    this.evidence = evidence;
    this.contexts = contexts;
    this.mapper = mapper;
  }

  @PostMapping(path = "/status", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<EvidenceRepresentation> status(
      @PathVariable String transportRequestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "X-External-Message-ID", required = false) String messageId,
      HttpServletRequest request) {
    PartnerProfileVersion profile = requireProfile(actor);
    requireMessageId(messageId);
    JsonNode body = read(request, profile, STATUS_FIELDS);
    StatusInput input = statusInput(body, profile);
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    TransportEvidenceCommandResult result =
        evidence.recordStatus(
            context,
            parseRequestId(transportRequestId),
            input.input(),
            messageId,
            RequestFingerprint.from(mapper.valueToTree(body)));
    return response(result);
  }

  @PostMapping(path = "/assignments", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<EvidenceRepresentation> assignment(
      @PathVariable String transportRequestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "X-External-Message-ID", required = false) String messageId,
      HttpServletRequest request) {
    PartnerProfileVersion profile = requireProfile(actor);
    requireMessageId(messageId);
    JsonNode body = read(request, profile, ASSIGNMENT_FIELDS);
    String observationId = identifier(body, "observationId", 128);
    String assignmentReference = identifier(body, "assignmentReference", 256);
    Instant occurredAt = instant(body, "occurredAt");
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    TransportEvidenceCommandResult result =
        evidence.recordAssignment(
            context,
            parseRequestId(transportRequestId),
            new TransportEvidenceCommands.AssignmentInput(
                namespace(profile), observationId, assignmentReference, occurredAt),
            messageId,
            RequestFingerprint.from(mapper.valueToTree(body)));
    return response(result);
  }

  @PostMapping(path = "/eta", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<EvidenceRepresentation> eta(
      @PathVariable String transportRequestId,
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestHeader(value = "X-AyuVahak-Organization-Id", required = false) String organizationId,
      @RequestHeader(value = "X-AyuVahak-Facility-Id", required = false) String facilityId,
      @RequestHeader(value = "X-External-Message-ID", required = false) String messageId,
      HttpServletRequest request) {
    PartnerProfileVersion profile = requireProfile(actor);
    requireMessageId(messageId);
    JsonNode body = read(request, profile, ETA_FIELDS);
    String observationId = identifier(body, "observationId", 128);
    Instant estimatedArrivalAt = instant(body, "estimatedArrivalAt");
    Instant observedAt = instant(body, "observedAt");
    ExecutionContext context = context(actor, scope, organizationId, facilityId);
    TransportEvidenceCommandResult result =
        evidence.recordEta(
            context,
            parseRequestId(transportRequestId),
            new TransportEvidenceCommands.EtaInput(
                namespace(profile), observationId, estimatedArrivalAt, observedAt),
            messageId,
            RequestFingerprint.from(mapper.valueToTree(body)));
    return response(result);
  }

  private PartnerProfileVersion requireProfile(ActorReference actor) {
    if (actor == null || actor.kind() != ActorKind.SERVICE) {
      throw problem(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified provider service identity is required.");
    }
    try {
      return profiles
          .findActive(actor, AllowedOperation.transportProviderEvidence())
          .orElseThrow(TransportProviderEvidenceController::denied);
    } catch (PartnerTrustLookupUnavailableException unavailable) {
      throw problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.TRANSPORT_UNAVAILABLE,
          "Provider trust could not be checked; no evidence was accepted.");
    }
  }

  private JsonNode read(
      HttpServletRequest request, PartnerProfileVersion profile, Set<String> allowedFields) {
    int maximum =
        Math.min(profile.configuration().limits().maxResponseBytes(), HARD_MAX_BODY_BYTES);
    long declaredLength = request.getContentLengthLong();
    if (declaredLength > maximum) {
      throw invalid("Provider evidence exceeds the configured request limit.");
    }
    byte[] bodyBytes = readBounded(request, maximum);
    JsonNode body;
    try {
      body = mapper.readTree(bodyBytes);
    } catch (JacksonException malformed) {
      throw invalid("Provider evidence is not valid bounded JSON.");
    }
    if (body == null
        || !body.isObject()
        || body.properties().stream().anyMatch(field -> !allowedFields.contains(field.getKey()))) {
      throw invalid("Provider evidence contains unsupported fields.");
    }
    if (maximumDepth(body, 0) > Math.min(profile.configuration().limits().maxNestingDepth(), 12)) {
      throw invalid("Provider evidence exceeds the configured nesting limit.");
    }
    return body;
  }

  private StatusInput statusInput(JsonNode body, PartnerProfileVersion profile) {
    String observationId = identifier(body, "observationId", 128);
    JsonNode sequence = body.get("sourceSequence");
    if (sequence != null && !sequence.isNull() && !sequence.canConvertToLong()) {
      throw invalid("Provider status sequence is invalid.");
    }
    String statusValue = requiredText(body, "status", 64);
    TransportRequest.Status status;
    try {
      status = TransportRequest.Status.valueOf(statusValue);
    } catch (IllegalArgumentException malformed) {
      throw invalid("Provider status is unsupported.");
    }
    if (!Set.of(
            TransportRequest.Status.AWAITING_ASSIGNMENT,
            TransportRequest.Status.EN_ROUTE_TO_ORIGIN,
            TransportRequest.Status.AT_ORIGIN,
            TransportRequest.Status.PATIENT_ONBOARD,
            TransportRequest.Status.EN_ROUTE_TO_DESTINATION,
            TransportRequest.Status.ARRIVED_DESTINATION,
            TransportRequest.Status.INTERRUPTED)
        .contains(status)) {
      throw invalid("Provider status requires a dedicated Transport operation.");
    }
    OptionalLong sourceSequence =
        sequence == null || sequence.isNull()
            ? OptionalLong.empty()
            : OptionalLong.of(sequence.longValue());
    Optional<String> reason =
        Optional.ofNullable(body.get("reasonCode"))
            .filter(value -> !value.isNull())
            .map(JsonNode::asString);
    reason.ifPresent(
        value -> {
          if (!value.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw invalid("Provider status reason code is invalid.");
          }
        });
    return new StatusInput(
        new TransportEvidenceCommands.StatusInput(
            namespace(profile),
            observationId,
            sourceSequence,
            status,
            instant(body, "occurredAt"),
            reason));
  }

  private ExecutionContext context(
      ActorReference actor, String scope, String organizationId, String facilityId) {
    if (!"FACILITY".equals(scope) || organizationId == null || facilityId == null) {
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
      throw invalidScope();
    }
  }

  private static String namespace(PartnerProfileVersion profile) {
    return "urn:ayuvahak:partner:" + profile.configuration().partnerKey().value();
  }

  private static byte[] readBounded(HttpServletRequest request, int maximum) {
    try (var input = request.getInputStream();
        var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        if (count == 0) continue;
        if (count > maximum - output.size()) {
          throw invalid("Provider evidence exceeds the configured request limit.");
        }
        output.write(buffer, 0, count);
      }
      if (output.size() == 0) throw invalid("Provider evidence body is required.");
      return output.toByteArray();
    } catch (IOException failure) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "Provider evidence could not be read safely.");
    }
  }

  private static int maximumDepth(JsonNode node, int depth) {
    if (node == null || node.isValueNode()) return depth;
    int maximum = depth;
    for (JsonNode child : node) maximum = Math.max(maximum, maximumDepth(child, depth + 1));
    return maximum;
  }

  private static String identifier(JsonNode body, String field, int maximumLength) {
    String value = requiredText(body, field, maximumLength);
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid("Provider evidence identifier is invalid.");
    }
    return value;
  }

  private static String requiredText(JsonNode body, String field, int maximumLength) {
    JsonNode value = body.get(field);
    if (value == null
        || !value.isString()
        || value.asString().isBlank()
        || value.asString().length() > maximumLength
        || !value.asString().equals(value.asString().strip())) {
      throw invalid("Provider evidence is missing a bounded " + field + ".");
    }
    return value.asString();
  }

  private static Instant instant(JsonNode body, String field) {
    String value = requiredText(body, field, 64);
    try {
      return Instant.parse(value);
    } catch (RuntimeException malformed) {
      throw invalid("Provider evidence time is invalid.");
    }
  }

  private static void requireMessageId(String messageId) {
    if (messageId == null
        || messageId.isEmpty()
        || messageId.length() > 128
        || messageId.codePoints().anyMatch(codePoint -> codePoint < 0x21 || codePoint > 0x7e)) {
      throw invalid("A bounded external message identity is required.");
    }
  }

  private static InternalId parseRequestId(String value) {
    try {
      return InternalId.parse(value);
    } catch (RuntimeException malformed) {
      throw invalid("A valid Transport request identifier is required.");
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

  private static ResponseEntity<EvidenceRepresentation> response(
      TransportEvidenceCommandResult result) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(CorrelationIdFilter.HEADER_NAME, correlationId().value());
    if (result.commandReplayed()) headers.set("Idempotency-Replayed", "true");
    if (result.sourceReplayed()) headers.set("Source-Observation-Replayed", "true");
    return new ResponseEntity<>(
        new EvidenceRepresentation(
            result.observationId().toString(),
            result.transportRequestId().toString(),
            result.requestStatus(),
            result.evidenceOutcome()),
        headers,
        HttpStatus.CREATED);
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException denied() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated partner is not active for this Transport evidence operation.");
  }

  private static PlatformProblemException invalidScope() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.INVALID_EXECUTION_SCOPE,
        "Provider evidence requires a validated origin Facility scope.");
  }

  private static PlatformProblemException invalid(String detail) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY, PlatformErrorCode.TRANSPORT_EVIDENCE_INVALID, detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private record StatusInput(TransportEvidenceCommands.StatusInput input) {}

  public record EvidenceRepresentation(
      String observationId, String transportRequestId, String requestStatus, String outcome) {}
}
