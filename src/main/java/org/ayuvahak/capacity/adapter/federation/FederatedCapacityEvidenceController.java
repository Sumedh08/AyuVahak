package org.ayuvahak.capacity.adapter.federation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.ayuvahak.capacity.application.CapacityEvidenceQueries;
import org.ayuvahak.capacity.application.CapacityFederatedEvidence;
import org.ayuvahak.capacity.domain.CapacityDimensionReadiness;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorization;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorizationException;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorizationService;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeDeniedException;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixed peer-authenticated Capacity evidence contract; it is not a general readiness endpoint. */
@RestController
@RequestMapping("/api/federation/operations")
final class FederatedCapacityEvidenceController {

  private static final String CONTRACT_VERSION = "capacity-remote-evidence-v1";
  private static final String PROFILE_VERSION = "capacity-evidence-v1";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String SOURCE_HEADER = "X-AyuVahak-Federation-Source";
  private static final String PEER_HEADER = "X-AyuVahak-Federation-Peer";
  private static final String OPERATION_HEADER = "X-AyuVahak-Federation-Operation";
  private static final String OPERATION_ID_HEADER = "X-AyuVahak-Operation-Id";
  private static final String FINGERPRINT_HEADER = "X-AyuVahak-Operation-Fingerprint";
  private static final int MAX_REQUEST_BYTES = 16_384;
  private static final Set<String> REQUEST_FIELDS =
      Set.of("contractVersion", "deploymentId", "context", "resourceId", "dimensionCode");
  private static final AllowedOperation OPERATION =
      AllowedOperation.capacityRemoteEvidence(Direction.INBOUND);

  private final FederationPeerInboundAuthorizationService peerAuthorization;
  private final DeploymentIdentityRegistrationService deploymentIdentity;
  private final ExecutionContextFactory contexts;
  private final CapacityEvidenceQueries evidence;
  private final ObjectMapper mapper;
  private final Clock clock;

  FederatedCapacityEvidenceController(
      FederationPeerInboundAuthorizationService peerAuthorization,
      DeploymentIdentityRegistrationService deploymentIdentity,
      ExecutionContextFactory contexts,
      CapacityEvidenceQueries evidence,
      ObjectMapper mapper,
      @Qualifier("auditClock") Clock clock) {
    this.peerAuthorization = java.util.Objects.requireNonNull(peerAuthorization);
    this.deploymentIdentity = java.util.Objects.requireNonNull(deploymentIdentity);
    this.contexts = java.util.Objects.requireNonNull(contexts);
    this.evidence = java.util.Objects.requireNonNull(evidence);
    this.mapper = java.util.Objects.requireNonNull(mapper);
    this.clock = java.util.Objects.requireNonNull(clock);
  }

  @PostMapping(
      path = "/capacity.remote-evidence",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<byte[]> read(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(SOURCE_HEADER) String sourceHeader,
      @RequestHeader(PEER_HEADER) String peerHeader,
      @RequestHeader(OPERATION_HEADER) String operationHeader,
      @RequestHeader(OPERATION_ID_HEADER) String operationIdHeader,
      @RequestHeader(FINGERPRINT_HEADER) String fingerprintHeader,
      HttpServletRequest request) {
    DeploymentId source = parseDeployment(sourceHeader);
    DeploymentId local = localDeployment();
    if (actor == null
        || actor.kind() != ActorKind.SERVICE
        || !local.value().equals(peerHeader)
        || !OPERATION.operationCode().equals(operationHeader)) {
      throw denied();
    }

    byte[] body = boundedJson(request);
    JsonNode document = parseJson(body);
    requireFields(document);
    String deploymentId = requiredText(document, "deploymentId");
    String context = requiredText(document, "context");
    String resourceId = requiredText(document, "resourceId");
    String dimensionCode = requiredText(document, "dimensionCode");
    if (!CONTRACT_VERSION.equals(requiredText(document, "contractVersion"))
        || !local.value().equals(deploymentId)
        || !"directory".equals(context)
        || !dimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw malformed();
    }

    InternalId facilityId = parseId(resourceId);
    ExternalReference peerScope =
        new ExternalReference(
            new IssuerId("urn:ayuvahak:deployment:" + local.value() + ":directory"),
            "Facility",
            facilityId.toString());
    InternalId operationId = parseId(operationIdHeader);
    FederationOperationFingerprint suppliedFingerprint = parseFingerprint(fingerprintHeader);
    FederationOperationFingerprint expectedFingerprint =
        FederationOperationFingerprint.calculate(source, local, peerScope, OPERATION, body);
    if (!suppliedFingerprint.equals(expectedFingerprint)) {
      throw malformed();
    }

    FederationPeerInboundAuthorization peer;
    try {
      peer = peerAuthorization.authorize(actor, source, OPERATION, peerScope);
    } catch (FederationPeerInboundAuthorizationException failure) {
      if (failure.failure()
          == FederationPeerInboundAuthorizationException.Failure.TRUST_UNAVAILABLE) {
        throw unavailable();
      }
      throw denied();
    }

    ExternalReference localOrganization =
        localDirectoryReference("Organization", peer.organizationScope().value());
    ExternalReference localFacility = localDirectoryReference("Facility", facilityId.toString());
    try {
      var contextForPeer =
          contexts.forIntegrationRequest(
              peer.authenticatedPeerPrincipal(),
              RequestedExecutionScope.facility(localOrganization, localFacility),
              new CorrelationId(operationId.toString()));
      var result = evidence.currentFederated(contextForPeer, localFacility, dimensionCode);
      return response(result, local, localFacility);
    } catch (ExecutionScopeDeniedException | IllegalArgumentException denied) {
      throw denied();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  private ResponseEntity<byte[]> response(
      CapacityFederatedEvidence capacityEvidence,
      DeploymentId deployment,
      ExternalReference facility) {
    CapacityDimensionReadiness readiness = capacityEvidence.readiness();
    if (readiness.dimensionVersion().isEmpty() || readiness.policyVersion().isEmpty()) {
      throw unavailable();
    }
    Instant now = clock.instant();
    Instant observedAt =
        readiness.evidence().stream()
            .map(org.ayuvahak.capacity.domain.CapacityObservation::observedAt)
            .max(Instant::compareTo)
            .orElse(now);
    Instant policyFreshnessDeadline =
        observedAt.plusSeconds(capacityEvidence.maximumObservationAgeSeconds());
    Instant evidenceValidityDeadline =
        readiness.evidence().stream()
            .flatMap(observation -> observation.validUntil().stream())
            .min(Instant::compareTo)
            .orElse(policyFreshnessDeadline);
    Instant validUntil =
        evidenceValidityDeadline.isBefore(policyFreshnessDeadline)
            ? evidenceValidityDeadline
            : policyFreshnessDeadline;
    boolean complete =
        readiness.freshness() == org.ayuvahak.capacity.domain.CapacityEvidenceFreshness.CURRENT
            && (readiness.outcome() == org.ayuvahak.capacity.domain.CapacityReadinessOutcome.READY
                || readiness.outcome()
                    == org.ayuvahak.capacity.domain.CapacityReadinessOutcome.NOT_READY);
    ObjectNode document = mapper.createObjectNode();
    document.put("contractVersion", CONTRACT_VERSION);
    document.put("deploymentId", deployment.value());
    document.put("context", "directory");
    document.put("resourceId", facility.value());
    document.put("dimensionCode", readiness.dimensionCode());
    document.put("sourceSequence", capacityEvidence.sourceSequence());
    document.put("dimensionVersion", readiness.dimensionVersion().getAsLong());
    document.put("policyVersion", readiness.policyVersion().getAsLong());
    document.put("sourceProfileVersion", PROFILE_VERSION);
    document.put(
        "evidenceId",
        readiness.evidence().stream()
            .findFirst()
            .map(item -> item.id().toString())
            .orElse("CAPACITY-EVIDENCE-" + capacityEvidence.sourceSequence()));
    document.put("outcome", readiness.outcome().name());
    document.put("freshness", readiness.freshness().name());
    document.put("complete", complete);
    if (complete && readiness.quantity().isPresent()) {
      document.put("quantity", readiness.quantity().orElseThrow());
    } else {
      document.putNull("quantity");
    }
    document.put("state", complete ? readiness.state().orElse(null) : null);
    document.put("unit", complete ? readiness.unit().orElse(null) : null);
    document.put("observedAt", observedAt.toString());
    document.put("validUntil", validUntil.toString());
    try {
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(mapper.writeValueAsBytes(document));
    } catch (JacksonException failure) {
      throw unavailable();
    }
  }

  private byte[] boundedJson(HttpServletRequest request) {
    if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
      throw tooLarge();
    }
    String encoding = request.getHeader("Content-Encoding");
    if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding)) {
      throw unsupportedMediaType();
    }
    String contentType = request.getContentType();
    try {
      if (contentType == null
          || !MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON)) {
        throw unsupportedMediaType();
      }
    } catch (IllegalArgumentException invalid) {
      throw unsupportedMediaType();
    }
    try (var input = request.getInputStream();
        var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        total = Math.addExact(total, read);
        if (total > MAX_REQUEST_BYTES) {
          throw tooLarge();
        }
        output.write(buffer, 0, read);
      }
      if (total == 0) {
        throw malformed();
      }
      return output.toByteArray();
    } catch (IOException | ArithmeticException failure) {
      throw malformed();
    }
  }

  private JsonNode parseJson(byte[] body) {
    try {
      String value =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      JsonNode parsed = mapper.readTree(value);
      if (parsed == null || !parsed.isObject()) {
        throw malformed();
      }
      return parsed;
    } catch (CharacterCodingException | JacksonException invalid) {
      throw malformed();
    }
  }

  private static void requireFields(JsonNode document) {
    Set<String> fields = new java.util.HashSet<>();
    document
        .properties()
        .forEach(
            field -> {
              if (!REQUEST_FIELDS.contains(field.getKey())) {
                throw malformed();
              }
              fields.add(field.getKey());
            });
    if (!fields.equals(REQUEST_FIELDS)) {
      throw malformed();
    }
  }

  private static String requiredText(JsonNode document, String field) {
    JsonNode value = document.get(field);
    if (value == null || !value.isTextual() || value.asString().isBlank()) {
      throw malformed();
    }
    return value.asString();
  }

  private DeploymentId localDeployment() {
    try {
      return deploymentIdentity
          .current()
          .map(identity -> identity.deploymentId())
          .orElseThrow(FederatedCapacityEvidenceController::unavailable);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private static ExternalReference localDirectoryReference(String type, String value) {
    return new ExternalReference(new IssuerId(DIRECTORY_ISSUER), type, value);
  }

  private static DeploymentId parseDeployment(String value) {
    try {
      return new DeploymentId(value);
    } catch (IllegalArgumentException invalid) {
      throw malformed();
    }
  }

  private static InternalId parseId(String value) {
    try {
      return InternalId.parse(value);
    } catch (IllegalArgumentException invalid) {
      throw malformed();
    }
  }

  private static FederationOperationFingerprint parseFingerprint(String value) {
    try {
      return new FederationOperationFingerprint(value);
    } catch (IllegalArgumentException invalid) {
      throw malformed();
    }
  }

  private static PlatformProblemException malformed() {
    return problem(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.MALFORMED_REQUEST,
        "The named federated Capacity evidence request is malformed.");
  }

  private static PlatformProblemException tooLarge() {
    return problem(
        HttpStatus.PAYLOAD_TOO_LARGE,
        PlatformErrorCode.VALIDATION_FAILED,
        "The federated Capacity evidence request exceeds its size limit.");
  }

  private static PlatformProblemException unsupportedMediaType() {
    return problem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE,
        "Federated Capacity evidence accepts uncompressed application/json only.");
  }

  private static PlatformProblemException denied() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated peer is not authorized for this Capacity evidence request.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.CAPACITY_UNAVAILABLE,
        "Federated Capacity evidence could not be read safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
