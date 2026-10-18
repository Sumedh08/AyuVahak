package org.ayuvahak.directory.adapter.federation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ayuvahak.directory.application.DirectoryApplicationService;
import org.ayuvahak.directory.application.DirectoryApplicationService.DirectoryFacilitySearchPage;
import org.ayuvahak.directory.domain.Facility;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Named, bounded peer read that delegates all Directory facts to Directory owner queries. */
@RestController
@RequestMapping("/api/federation/operations")
final class FederatedDirectoryDiscoveryController {

  private static final String CONTRACT_PROFILE = "AyuVahak-Federation-v1";
  private static final String SOURCE_POLICY_VERSION = "directory-owner-v1";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String SOURCE_HEADER = "X-AyuVahak-Federation-Source";
  private static final String PEER_HEADER = "X-AyuVahak-Federation-Peer";
  private static final String OPERATION_HEADER = "X-AyuVahak-Federation-Operation";
  private static final String OPERATION_ID_HEADER = "X-AyuVahak-Operation-Id";
  private static final String FINGERPRINT_HEADER = "X-AyuVahak-Operation-Fingerprint";
  private static final int MAX_REQUEST_BYTES = 16_384;
  private static final int MAX_FACTS = 100;
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "protocolProfile",
          "sourceDeploymentId",
          "requestedScope",
          "resourceTypes",
          "maximumFacts");
  private static final AllowedOperation OPERATION =
      AllowedOperation.directoryRemoteDiscovery(Direction.INBOUND);

  private final FederationPeerInboundAuthorizationService peerAuthorization;
  private final DeploymentIdentityRegistrationService deploymentIdentity;
  private final ExecutionContextFactory contexts;
  private final DirectoryApplicationService directory;
  private final ObjectMapper mapper;
  private final Clock clock;

  FederatedDirectoryDiscoveryController(
      FederationPeerInboundAuthorizationService peerAuthorization,
      DeploymentIdentityRegistrationService deploymentIdentity,
      ExecutionContextFactory contexts,
      DirectoryApplicationService directory,
      ObjectMapper mapper,
      @Qualifier("auditClock") Clock clock) {
    this.peerAuthorization = java.util.Objects.requireNonNull(peerAuthorization);
    this.deploymentIdentity = java.util.Objects.requireNonNull(deploymentIdentity);
    this.contexts = java.util.Objects.requireNonNull(contexts);
    this.directory = java.util.Objects.requireNonNull(directory);
    this.mapper = java.util.Objects.requireNonNull(mapper);
    this.clock = java.util.Objects.requireNonNull(clock);
  }

  @PostMapping(
      path = "/directory.remote-discovery",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<byte[]> discover(
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
    if (!CONTRACT_PROFILE.equals(requiredText(document, "protocolProfile"))
        || !source.value().equals(requiredText(document, "sourceDeploymentId"))) {
      throw malformed();
    }
    JsonNode scopeNode = document.get("requestedScope");
    if (scopeNode == null || !scopeNode.isObject()) {
      throw malformed();
    }
    requireExactObjectFields(scopeNode, Set.of("issuer", "resourceType", "value"));
    String issuer = requiredText(scopeNode, "issuer");
    String resourceType = requiredText(scopeNode, "resourceType");
    String resourceId = requiredText(scopeNode, "value");
    String expectedIssuer = "urn:ayuvahak:deployment:" + local.value() + ":directory";
    if (!expectedIssuer.equals(issuer)
        || (!"Organization".equals(resourceType) && !"Facility".equals(resourceType))) {
      throw malformed();
    }
    InternalId scopeId = parseId(resourceId);
    ExternalReference peerScope =
        new ExternalReference(new IssuerId(issuer), resourceType, resourceId);
    JsonNode resourceTypes = document.get("resourceTypes");
    List<String> requestedTypes = parseResourceTypes(resourceTypes, resourceType);
    JsonNode maximumFactsNode = document.get("maximumFacts");
    if (maximumFactsNode == null || !maximumFactsNode.isIntegralNumber()) {
      throw malformed();
    }
    int maximumFacts = maximumFactsNode.intValue();
    if (maximumFacts < 1 || maximumFacts > MAX_FACTS) {
      throw malformed();
    }

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

    ExternalReference organization =
        localReference("Organization", peer.organizationScope().value());
    ExternalReference facility =
        "Facility".equals(resourceType) ? localReference("Facility", scopeId.toString()) : null;
    try {
      var context =
          contexts.forIntegrationRequest(
              peer.authenticatedPeerPrincipal(),
              facility == null
                  ? RequestedExecutionScope.organization(organization)
                  : RequestedExecutionScope.facility(organization, facility),
              new CorrelationId(operationId.toString()));
      Instant observedAt = clock.instant();
      List<FactValue> facts = new ArrayList<>();
      boolean complete = true;
      if ("Organization".equals(resourceType)) {
        if (!requestedTypes.equals(List.of("Organization", "Facility"))) {
          throw malformed();
        }
        var ownerOrganization = directory.getOrganization(context, scopeId);
        facts.add(
            new FactValue(
                "Organization",
                ownerOrganization.id().toString(),
                null,
                ownerOrganization.name(),
                ownerOrganization.status().name().equals("ACTIVE"),
                ownerOrganization.version(),
                ownerOrganization.provenance().observedAt()));
        int facilitiesRemaining = maximumFacts - 1;
        if (facilitiesRemaining > 0) {
          DirectoryFacilitySearchPage page =
              directory.findFacilities(
                  context,
                  java.util.Optional.of(scopeId),
                  java.util.Optional.empty(),
                  List.of(),
                  0,
                  facilitiesRemaining);
          var candidates = page.facilities();
          int included = Math.min(facilitiesRemaining, candidates.size());
          for (int index = 0; index < included; index++) {
            var facilityFact = candidates.get(index).facility();
            facts.add(
                new FactValue(
                    "Facility",
                    facilityFact.id().toString(),
                    facilityFact.managingOrganizationId().toString(),
                    facilityFact.name(),
                    "ACTIVE".equals(facilityFact.status().name()),
                    facilityFact.version(),
                    facilityFact.provenance().observedAt()));
          }
          complete = !page.hasMore();
        } else {
          DirectoryFacilitySearchPage page =
              directory.findFacilities(
                  context,
                  java.util.Optional.of(scopeId),
                  java.util.Optional.empty(),
                  List.of(),
                  0,
                  1);
          complete = page.facilities().isEmpty() && !page.hasMore();
        }
      } else {
        if (!requestedTypes.equals(List.of("Facility"))) {
          throw malformed();
        }
        Facility facilityFact = directory.getFacility(context, scopeId).facility();
        facts.add(
            new FactValue(
                "Facility",
                facilityFact.id().toString(),
                facilityFact.managingOrganizationId().toString(),
                facilityFact.name(),
                "ACTIVE".equals(facilityFact.status().name()),
                facilityFact.version(),
                facilityFact.provenance().observedAt()));
      }
      return response(peerScope, observedAt, complete, facts);
    } catch (ExecutionScopeDeniedException denied) {
      throw denied();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  private ResponseEntity<byte[]> response(
      ExternalReference requestedScope,
      Instant observedAt,
      boolean complete,
      List<FactValue> facts) {
    ObjectNode document = mapper.createObjectNode();
    document.put("protocolProfile", CONTRACT_PROFILE);
    document.put("sourceDeploymentId", localDeployment().value());
    document.put("requestedScopeIssuer", requestedScope.issuer().value());
    document.put("requestedScopeResourceType", requestedScope.resourceType());
    document.put("requestedScopeValue", requestedScope.value());
    document.put("sourcePolicyVersion", SOURCE_POLICY_VERSION);
    document.put("observedAt", observedAt.toString());
    document.put("completeness", complete ? "COMPLETE" : "PARTIAL");
    ArrayNode items = document.putArray("facts");
    for (FactValue fact : facts) {
      ObjectNode item = items.addObject();
      item.put("resourceType", fact.resourceType());
      item.put("resourceId", fact.resourceId());
      if (fact.organizationResourceId() == null) {
        item.putNull("organizationResourceId");
      } else {
        item.put("organizationResourceId", fact.organizationResourceId());
      }
      item.put("displayName", fact.displayName());
      item.put("active", fact.active());
      item.put("sourceVersion", fact.sourceVersion());
      if (fact.sourceUpdatedAt() == null) {
        item.putNull("sourceUpdatedAt");
      } else {
        item.put("sourceUpdatedAt", fact.sourceUpdatedAt().toString());
      }
    }
    try {
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(mapper.writeValueAsBytes(document));
    } catch (JacksonException failure) {
      throw unavailable();
    }
  }

  private static List<String> parseResourceTypes(JsonNode node, String scopeType) {
    if (node == null || !node.isArray() || node.isEmpty() || node.size() > 2) {
      throw malformed();
    }
    List<String> values = new ArrayList<>();
    for (JsonNode value : node) {
      if (!value.isTextual()) {
        throw malformed();
      }
      values.add(value.asString());
    }
    if (new HashSet<>(values).size() != values.size()) {
      throw malformed();
    }
    List<String> expected =
        "Organization".equals(scopeType)
            ? List.of("Organization", "Facility")
            : List.of("Facility");
    if (!values.equals(expected)) {
      throw malformed();
    }
    return List.copyOf(values);
  }

  private static void requireFields(JsonNode document) {
    requireExactObjectFields(document, REQUEST_FIELDS);
  }

  private static void requireExactObjectFields(JsonNode object, Set<String> expected) {
    Set<String> fields = new HashSet<>();
    object
        .properties()
        .forEach(
            field -> {
              if (!expected.contains(field.getKey())) {
                throw malformed();
              }
              fields.add(field.getKey());
            });
    if (!fields.equals(expected)) {
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

  private DeploymentId localDeployment() {
    try {
      return deploymentIdentity
          .current()
          .map(identity -> identity.deploymentId())
          .orElseThrow(FederatedDirectoryDiscoveryController::unavailable);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private static ExternalReference localReference(String type, String value) {
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
        "The named federated Directory discovery request is malformed.");
  }

  private static PlatformProblemException tooLarge() {
    return problem(
        HttpStatus.PAYLOAD_TOO_LARGE,
        PlatformErrorCode.VALIDATION_FAILED,
        "The federated Directory discovery request exceeds its size limit.");
  }

  private static PlatformProblemException unsupportedMediaType() {
    return problem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE,
        "Federated Directory discovery accepts uncompressed application/json only.");
  }

  private static PlatformProblemException denied() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated peer is not authorized for this Directory discovery request.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.DIRECTORY_UNAVAILABLE,
        "Federated Directory discovery could not be completed safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private record FactValue(
      String resourceType,
      String resourceId,
      String organizationResourceId,
      String displayName,
      boolean active,
      long sourceVersion,
      Instant sourceUpdatedAt) {}
}
