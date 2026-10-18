package org.ayuvahak.analytics.adapter.federation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import org.ayuvahak.analytics.application.FederatedAggregateException;
import org.ayuvahak.analytics.application.FederatedAggregateReceiptService;
import org.ayuvahak.analytics.domain.FederatedAggregateEnvelope;
import org.ayuvahak.analytics.domain.FederatedAggregateReceipt;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The sole inbound route for aggregate snapshots; it has a fixed contract and bounded body. */
@RestController
@RequestMapping("/api/federation/operations")
final class FederatedAggregateOperationController {

  private static final String SOURCE_HEADER = "X-AyuVahak-Federation-Source";
  private static final String PEER_HEADER = "X-AyuVahak-Federation-Peer";
  private static final String OPERATION_HEADER = "X-AyuVahak-Federation-Operation";
  private static final String OPERATION_ID_HEADER = "X-AyuVahak-Operation-Id";
  private static final String FINGERPRINT_HEADER = "X-AyuVahak-Operation-Fingerprint";
  private static final AllowedOperation OPERATION =
      AllowedOperation.analyticsAggregateSnapshot(Direction.INBOUND);

  private final FederationPeerInboundAuthorizationService peerAuthorization;
  private final DeploymentIdentityRegistrationService deploymentIdentity;
  private final FederatedAggregateReceiptService receipts;
  private final FederatedAggregateEnvelopeJsonMapper mapper;

  FederatedAggregateOperationController(
      FederationPeerInboundAuthorizationService peerAuthorization,
      DeploymentIdentityRegistrationService deploymentIdentity,
      FederatedAggregateReceiptService receipts,
      FederatedAggregateEnvelopeJsonMapper mapper) {
    this.peerAuthorization = Objects.requireNonNull(peerAuthorization, "peer authorization");
    this.deploymentIdentity = Objects.requireNonNull(deploymentIdentity, "deployment identity");
    this.receipts = Objects.requireNonNull(receipts, "aggregate receipt service");
    this.mapper = Objects.requireNonNull(mapper, "aggregate envelope mapper");
  }

  @PostMapping(
      path = "/analytics.aggregate-snapshot",
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
    validateTransportHeaders(actor, sourceHeader, peerHeader, operationHeader);
    byte[] body = readBoundedJson(request);
    FederatedAggregateEnvelope envelope;
    try {
      envelope = mapper.decode(body);
    } catch (FederatedAggregateEnvelopeException malformed) {
      throw malformed();
    }
    DeploymentId source = parseDeploymentId(sourceHeader);
    DeploymentId local = localDeployment();
    if (!source.value().equals(envelope.snapshot().sourceDeployment().value())) {
      throw malformed();
    }
    InternalId operationId = parseOperationId(operationIdHeader);
    FederationOperationFingerprint suppliedFingerprint = parseFingerprint(fingerprintHeader);
    FederationOperationFingerprint calculatedFingerprint =
        FederationOperationFingerprint.calculate(
            source, local, envelope.targetOrganization(), OPERATION, body);
    if (!suppliedFingerprint.equals(calculatedFingerprint)) {
      throw malformed();
    }
    ExternalReference localOrganization =
        new ExternalReference(
            new IssuerId("urn:ayuvahak:directory"),
            "Organization",
            envelope.targetOrganization().value());
    FederationPeerInboundAuthorization authorization;
    try {
      authorization = peerAuthorization.authorize(actor, source, OPERATION, localOrganization);
    } catch (FederationPeerInboundAuthorizationException failure) {
      if (failure.failure()
          == FederationPeerInboundAuthorizationException.Failure.TRUST_UNAVAILABLE) {
        throw unavailable();
      }
      throw denied();
    }
    FederatedAggregateReceipt receipt;
    try {
      receipt = receipts.receive(authorization, operationId.value(), suppliedFingerprint, envelope);
    } catch (FederatedAggregateException failure) {
      throw map(failure);
    }
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(mapper.encodeAcknowledgement(receipt, local));
  }

  private void validateTransportHeaders(
      ActorReference actor, String source, String peer, String operation) {
    if (actor == null || actor.kind() != ActorKind.SERVICE) {
      throw denied();
    }
    if (!localDeployment().value().equals(peer) || !OPERATION.operationCode().equals(operation)) {
      throw denied();
    }
    parseDeploymentId(source);
  }

  private DeploymentId localDeployment() {
    try {
      return deploymentIdentity
          .current()
          .map(identity -> identity.deploymentId())
          .orElseThrow(FederatedAggregateOperationController::unavailable);
    } catch (DataAccessException unavailable) {
      throw unavailable();
    }
  }

  private static byte[] readBoundedJson(HttpServletRequest request) {
    if (request.getContentLengthLong() > FederatedAggregateEnvelopeJsonMapper.MAX_BYTES) {
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
        if (total > FederatedAggregateEnvelopeJsonMapper.MAX_BYTES) {
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

  private static InternalId parseOperationId(String value) {
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

  private static DeploymentId parseDeploymentId(String value) {
    try {
      return new DeploymentId(value);
    } catch (IllegalArgumentException invalid) {
      throw malformed();
    }
  }

  private static PlatformProblemException map(FederatedAggregateException failure) {
    return switch (failure.reason()) {
      case NOT_AUTHORIZED -> denied();
      case STORE_UNAVAILABLE, TRUST_NOT_AVAILABLE -> unavailable();
      case MALFORMED -> malformed();
      case MAPPING_NOT_FOUND, MAPPING_NOT_EFFECTIVE, OPERATION_CONFLICT, RECEIPT_CONFLICT ->
          problem(
              HttpStatus.CONFLICT,
              PlatformErrorCode.EXCHANGE_REPLAY_CONFLICT,
              "The named aggregate operation conflicts with configured peer or receipt state.");
      default ->
          problem(
              HttpStatus.UNPROCESSABLE_ENTITY,
              PlatformErrorCode.VALIDATION_FAILED,
              "The named aggregate operation is incompatible with approved reporting configuration.");
    };
  }

  private static PlatformProblemException malformed() {
    return problem(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.MALFORMED_REQUEST,
        "The named Analytics aggregate operation is malformed or unsupported.");
  }

  private static PlatformProblemException tooLarge() {
    return problem(
        HttpStatus.PAYLOAD_TOO_LARGE,
        PlatformErrorCode.VALIDATION_FAILED,
        "The Analytics aggregate operation exceeds its configured size limit.");
  }

  private static PlatformProblemException unsupportedMediaType() {
    return problem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE,
        "Federated Analytics operations accept uncompressed application/json only.");
  }

  private static PlatformProblemException denied() {
    return problem(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The authenticated peer is not authorized for this aggregate operation.");
  }

  private static PlatformProblemException unavailable() {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        PlatformErrorCode.EXCHANGE_CONFIGURATION_UNAVAILABLE,
        "The named Analytics aggregate operation could not be completed safely.");
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }
}
