package org.ayuvahak.transfer.adapter.federation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorization;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorizationException;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorizationService;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.transfer.application.FederatedReceivingCoordinationCommands;
import org.ayuvahak.transfer.application.FederatedReceivingRequestInput;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest;
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

/** Fixed Phase 11 receiving contracts; this adapter exposes no generic peer webhook. */
@RestController
@RequestMapping("/api/federation/operations")
final class FederatedReceivingOperationController {

  private static final String PROFILE = "AyuVahak-Federation-v1";
  private static final String DIRECTORY_ISSUER = "urn:ayuvahak:directory";
  private static final String SOURCE_HEADER = "X-AyuVahak-Federation-Source";
  private static final String PEER_HEADER = "X-AyuVahak-Federation-Peer";
  private static final String OPERATION_HEADER = "X-AyuVahak-Federation-Operation";
  private static final String OPERATION_ID_HEADER = "X-AyuVahak-Operation-Id";
  private static final String FINGERPRINT_HEADER = "X-AyuVahak-Operation-Fingerprint";
  private static final int MAX_REQUEST_BYTES = 65_536;
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "protocolProfile",
          "sourceDeploymentId",
          "originContext",
          "originResourceType",
          "originJourneyId",
          "originFacilityId",
          "destinationOrganization",
          "destinationFacility",
          "capacityDimensionCode",
          "capacityUnit",
          "capacityQuantity",
          "capacityIntervalStart",
          "capacityIntervalEnd");
  private static final Set<String> STATUS_FIELDS =
      Set.of(
          "protocolProfile",
          "sourceDeploymentId",
          "operationId",
          "requestFingerprint",
          "destinationOrganization",
          "destinationFacility");
  private static final Set<String> REFERENCE_FIELDS = Set.of("issuer", "resourceType", "value");

  private static final AllowedOperation RECEIVING_REQUEST =
      AllowedOperation.transferRemoteReceivingRequest(Direction.INBOUND);
  private static final AllowedOperation RESERVATION_STATUS =
      AllowedOperation.transferRemoteReservationStatus(Direction.INBOUND);

  private final FederationPeerInboundAuthorizationService peerAuthorization;
  private final DeploymentIdentityRegistrationService deploymentIdentity;
  private final FederatedReceivingCoordinationCommands commands;
  private final ObjectMapper mapper;

  FederatedReceivingOperationController(
      FederationPeerInboundAuthorizationService peerAuthorization,
      DeploymentIdentityRegistrationService deploymentIdentity,
      FederatedReceivingCoordinationCommands commands,
      ObjectMapper mapper) {
    this.peerAuthorization = Objects.requireNonNull(peerAuthorization, "peer authorization");
    this.deploymentIdentity = Objects.requireNonNull(deploymentIdentity, "deployment identity");
    this.commands = Objects.requireNonNull(commands, "Transfer receiving commands");
    this.mapper = Objects.requireNonNull(mapper, "JSON mapper");
  }

  @PostMapping(
      path = "/transfer.remote-receiving-request",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<byte[]> receive(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(SOURCE_HEADER) String sourceHeader,
      @RequestHeader(PEER_HEADER) String peerHeader,
      @RequestHeader(OPERATION_HEADER) String operationHeader,
      @RequestHeader(OPERATION_ID_HEADER) String operationIdHeader,
      @RequestHeader(FINGERPRINT_HEADER) String fingerprintHeader,
      HttpServletRequest request) {
    validateTransportHeaders(actor, sourceHeader, peerHeader, operationHeader, RECEIVING_REQUEST);
    byte[] body = readBoundedJson(request);
    JsonNode json = parseJson(body);
    requireFields(json, REQUEST_FIELDS);
    DeploymentId sourceDeployment = parseDeploymentId(sourceHeader);
    if (!PROFILE.equals(requiredText(json, "protocolProfile"))
        || !sourceHeader.equals(requiredText(json, "sourceDeploymentId"))
        || !"transfer".equals(requiredText(json, "originContext"))
        || !"TransferJourney".equals(requiredText(json, "originResourceType"))) {
      throw malformed();
    }
    DeploymentId localDeployment = localDeployment();
    String expectedNamespace = directoryNamespace(localDeployment);
    ExternalReference remoteOrganization =
        parseReference(json.get("destinationOrganization"), "Organization", expectedNamespace);
    ExternalReference remoteFacility =
        parseReference(json.get("destinationFacility"), "Facility", expectedNamespace);
    ExternalReference localOrganization = directoryReference(remoteOrganization, "Organization");
    ExternalReference localFacility = directoryReference(remoteFacility, "Facility");
    InternalId operationId = parseInternalId(operationIdHeader);
    FederationOperationFingerprint fingerprint =
        parseAndVerifyFingerprint(
            fingerprintHeader,
            sourceDeployment,
            localDeployment,
            remoteFacility,
            RECEIVING_REQUEST,
            body);
    FederationPeerInboundAuthorization authorization =
        authorize(actor, sourceDeployment, RECEIVING_REQUEST, localFacility);

    FederatedReceivingRequestInput input;
    try {
      input =
          new FederatedReceivingRequestInput(
              InternalId.parse(requiredText(json, "originJourneyId")),
              requiredText(json, "originFacilityId"),
              localOrganization,
              localFacility,
              requiredText(json, "capacityDimensionCode"),
              requiredText(json, "capacityUnit"),
              positiveQuantity(json.get("capacityQuantity")),
              parseInstant(json, "capacityIntervalStart"),
              parseInstant(json, "capacityIntervalEnd"));
    } catch (IllegalArgumentException invalid) {
      throw malformed();
    }
    FederatedReceivingRequestResultRepresentation result =
        representation(
            operationId,
            commands.receive(authorization, operationId, fingerprint, input).request());
    return jsonResponse(result);
  }

  @PostMapping(
      path = "/transfer.remote-reservation-status",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<byte[]> reservationStatus(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(SOURCE_HEADER) String sourceHeader,
      @RequestHeader(PEER_HEADER) String peerHeader,
      @RequestHeader(OPERATION_HEADER) String operationHeader,
      @RequestHeader(OPERATION_ID_HEADER) String operationIdHeader,
      @RequestHeader(FINGERPRINT_HEADER) String fingerprintHeader,
      HttpServletRequest request) {
    validateTransportHeaders(actor, sourceHeader, peerHeader, operationHeader, RESERVATION_STATUS);
    byte[] body = readBoundedJson(request);
    JsonNode json = parseJson(body);
    requireFields(json, STATUS_FIELDS);
    DeploymentId sourceDeployment = parseDeploymentId(sourceHeader);
    if (!PROFILE.equals(requiredText(json, "protocolProfile"))
        || !sourceHeader.equals(requiredText(json, "sourceDeploymentId"))) {
      throw malformed();
    }
    DeploymentId localDeployment = localDeployment();
    String expectedNamespace = directoryNamespace(localDeployment);
    ExternalReference remoteOrganization =
        parseReference(json.get("destinationOrganization"), "Organization", expectedNamespace);
    ExternalReference remoteFacility =
        parseReference(json.get("destinationFacility"), "Facility", expectedNamespace);
    ExternalReference localOrganization = directoryReference(remoteOrganization, "Organization");
    ExternalReference localFacility = directoryReference(remoteFacility, "Facility");
    InternalId operationId = parseInternalId(operationIdHeader);
    if (!operationId.toString().equals(requiredText(json, "operationId"))) {
      throw malformed();
    }
    FederationOperationFingerprint requestFingerprint =
        parseFingerprint(requiredText(json, "requestFingerprint"));
    parseAndVerifyFingerprint(
        fingerprintHeader,
        sourceDeployment,
        localDeployment,
        remoteFacility,
        RESERVATION_STATUS,
        body);
    FederationPeerInboundAuthorization authorization =
        authorize(actor, sourceDeployment, RESERVATION_STATUS, localFacility);
    var result =
        commands
            .reservationStatus(
                authorization, operationId, requestFingerprint, localOrganization, localFacility)
            .request();
    return jsonResponse(representation(operationId, result));
  }

  private ResponseEntity<byte[]> jsonResponse(
      FederatedReceivingRequestResultRepresentation result) {
    try {
      ObjectNode response = mapper.createObjectNode();
      response.put("protocolProfile", PROFILE);
      response.put("sourceDeploymentId", result.localDeployment().value());
      response.put("operationId", result.operationId().toString());
      response.put("requestFingerprint", result.requestFingerprint());
      response.put("outcome", result.request().status().name());
      response.put("receivingRequestId", result.request().id().toString());
      if (result.request().capacityReservationId().isPresent()) {
        response.put(
            "capacityReservationId",
            result.request().capacityReservationId().orElseThrow().toString());
      } else {
        response.putNull("capacityReservationId");
      }
      response.put("receivingVersion", result.request().version());
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(mapper.writeValueAsBytes(response));
    } catch (JacksonException failure) {
      throw unavailable();
    }
  }

  private FederatedReceivingRequestResultRepresentation representation(
      InternalId operationId, FederatedReceivingRequest request) {
    return new FederatedReceivingRequestResultRepresentation(
        localDeployment(), operationId, request.requestFingerprint(), request);
  }

  private FederationPeerInboundAuthorization authorize(
      ActorReference actor,
      DeploymentId source,
      AllowedOperation operation,
      ExternalReference localFacility) {
    try {
      return peerAuthorization.authorize(actor, source, operation, localFacility);
    } catch (FederationPeerInboundAuthorizationException denied) {
      if (denied.failure()
          == FederationPeerInboundAuthorizationException.Failure.TRUST_UNAVAILABLE) {
        throw unavailable();
      }
      throw denied();
    }
  }

  private void validateTransportHeaders(
      ActorReference actor,
      String sourceHeader,
      String peerHeader,
      String operationHeader,
      AllowedOperation expectedOperation) {
    if (actor == null || actor.kind() != ActorKind.SERVICE) {
      throw denied();
    }
    DeploymentId local = localDeployment();
    if (!local.value().equals(peerHeader)
        || !expectedOperation.operationCode().equals(operationHeader)) {
      throw denied();
    }
    parseDeploymentId(sourceHeader);
  }

  private DeploymentId localDeployment() {
    try {
      return deploymentIdentity
          .current()
          .map(identity -> identity.deploymentId())
          .orElseThrow(FederatedReceivingOperationController::unavailable);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private static FederationOperationFingerprint parseAndVerifyFingerprint(
      String header,
      DeploymentId source,
      DeploymentId local,
      ExternalReference remoteFacility,
      AllowedOperation operation,
      byte[] body) {
    FederationOperationFingerprint supplied = parseFingerprint(header);
    FederationOperationFingerprint calculated =
        FederationOperationFingerprint.calculate(source, local, remoteFacility, operation, body);
    if (!supplied.equals(calculated)) {
      throw malformed();
    }
    return supplied;
  }

  private static byte[] readBoundedJson(HttpServletRequest request) {
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
      byte[] buffer = new byte[8192];
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
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      JsonNode json = mapper.readTree(decoded);
      if (json == null || !json.isObject()) {
        throw malformed();
      }
      return json;
    } catch (CharacterCodingException | JacksonException invalid) {
      throw malformed();
    }
  }

  private static void requireFields(JsonNode json, Set<String> fields) {
    if (json == null || !json.isObject()) {
      throw malformed();
    }
    Set<String> present = new java.util.HashSet<>();
    json.properties()
        .forEach(
            field -> {
              if (!fields.contains(field.getKey())) {
                throw malformed();
              }
              present.add(field.getKey());
            });
    if (!present.equals(fields)) {
      throw malformed();
    }
  }

  private static ExternalReference parseReference(
      JsonNode json, String resourceType, String expectedIssuer) {
    requireFields(json, REFERENCE_FIELDS);
    String issuer = requiredText(json, "issuer");
    String type = requiredText(json, "resourceType");
    String value = requiredText(json, "value");
    if (!expectedIssuer.equals(issuer) || !resourceType.equals(type)) {
      throw denied();
    }
    try {
      InternalId.parse(value);
      return new ExternalReference(new IssuerId(issuer), type, value);
    } catch (IllegalArgumentException invalid) {
      throw malformed();
    }
  }

  private static ExternalReference directoryReference(
      ExternalReference remoteReference, String resourceType) {
    return new ExternalReference(
        new IssuerId(DIRECTORY_ISSUER), resourceType, remoteReference.value());
  }

  private static String directoryNamespace(DeploymentId deployment) {
    return "urn:ayuvahak:deployment:" + deployment.value() + ":directory";
  }

  private static DeploymentId parseDeploymentId(String value) {
    try {
      return new DeploymentId(value);
    } catch (IllegalArgumentException invalid) {
      throw malformed();
    }
  }

  private static InternalId parseInternalId(String value) {
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

  private static BigDecimal positiveQuantity(JsonNode value) {
    if (value == null || !value.isNumber()) {
      throw malformed();
    }
    BigDecimal quantity = value.decimalValue();
    if (quantity.signum() <= 0 || quantity.precision() > 12 || Math.max(0, quantity.scale()) > 6) {
      throw malformed();
    }
    return quantity;
  }

  private static Instant parseInstant(JsonNode json, String name) {
    try {
      return Instant.parse(requiredText(json, name));
    } catch (RuntimeException invalid) {
      throw malformed();
    }
  }

  private static String requiredText(JsonNode json, String field) {
    JsonNode value = json == null ? null : json.get(field);
    if (value == null || !value.isTextual() || value.asString().isBlank()) {
      throw malformed();
    }
    return value.asString();
  }

  private static PlatformProblemException malformed() {
    return problem(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.MALFORMED_REQUEST,
        "The named federated receiving operation is malformed or unsupported.");
  }

  private static PlatformProblemException tooLarge() {
    return problem(
        HttpStatus.PAYLOAD_TOO_LARGE,
        PlatformErrorCode.VALIDATION_FAILED,
        "The federated receiving operation exceeds its configured size limit.");
  }

  private static PlatformProblemException unsupportedMediaType() {
    return problem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE,
        "Federated receiving operations accept uncompressed application/json only.");
  }

  private static PlatformProblemException denied() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated peer is not authorized for this receiving operation.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
        "Federated receiving authorization is temporarily unavailable.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  private record FederatedReceivingRequestResultRepresentation(
      DeploymentId localDeployment,
      InternalId operationId,
      String requestFingerprint,
      FederatedReceivingRequest request) {}
}
