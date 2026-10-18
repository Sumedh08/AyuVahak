package org.ayuvahak.matching.adapter.federation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.ayuvahak.matching.application.FederationLocalDeploymentIdentity;
import org.ayuvahak.matching.application.RemoteCapacityEvidenceExchange;
import org.ayuvahak.matching.application.RemoteCapacityEvidenceExchangeException;
import org.ayuvahak.matching.application.RemoteCapacityPeerTarget;
import org.ayuvahak.matching.domain.RemoteCapacityEvidence;
import org.ayuvahak.matching.domain.RemoteCapacityOutcome;
import org.ayuvahak.matching.domain.RemoteEvidenceFreshness;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationException;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationFailure;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationPort;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationResponse;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Adapts Matching's named evidence request to the shared Phase 9 peer exchange port. */
@Component
public final class Phase9RemoteCapacityEvidenceAdapter implements RemoteCapacityEvidenceExchange {

  private static final int MAX_RESPONSE_BYTES = 65_536;
  private static final String CONTRACT_VERSION = "capacity-remote-evidence-v1";
  private static final AllowedOperation OPERATION =
      AllowedOperation.capacityRemoteEvidence(Direction.OUTBOUND);
  private static final Set<String> RESPONSE_FIELDS =
      Set.of(
          "contractVersion",
          "deploymentId",
          "context",
          "resourceId",
          "dimensionCode",
          "sourceSequence",
          "dimensionVersion",
          "policyVersion",
          "sourceProfileVersion",
          "evidenceId",
          "outcome",
          "freshness",
          "complete",
          "quantity",
          "state",
          "unit",
          "observedAt",
          "validUntil");

  private final FederationPeerOperationPort peerOperations;
  private final FederationLocalDeploymentIdentity deploymentIdentity;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public Phase9RemoteCapacityEvidenceAdapter(
      FederationPeerOperationPort peerOperations,
      FederationLocalDeploymentIdentity deploymentIdentity,
      ObjectMapper objectMapper,
      @Qualifier("auditClock") Clock clock) {
    this.peerOperations = java.util.Objects.requireNonNull(peerOperations, "peer operation port");
    this.deploymentIdentity =
        java.util.Objects.requireNonNull(deploymentIdentity, "deployment identity");
    this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "JSON mapper");
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
  }

  @Override
  public RemoteCapacityEvidence request(
      RemoteCapacityPeerTarget peer, RemoteFacilityReference facility, String dimensionCode) {
    java.util.Objects.requireNonNull(peer, "approved remote peer");
    java.util.Objects.requireNonNull(facility, "remote Facility reference");
    requireDimension(dimensionCode);
    if (!peer.peerDeploymentId().equals(facility.deploymentId())) {
      throw invalidResponse();
    }
    var localDeployment =
        deploymentIdentity
            .current()
            .orElseThrow(
                () ->
                    new RemoteCapacityEvidenceExchangeException(
                        RemoteCapacityEvidenceExchangeException.Reason
                            .LOCAL_DEPLOYMENT_IDENTITY_UNAVAILABLE));
    if (localDeployment.equals(peer.peerDeploymentId())) {
      throw new RemoteCapacityEvidenceExchangeException(
          RemoteCapacityEvidenceExchangeException.Reason.PEER_NOT_CONFIGURED);
    }

    byte[] body = encodeRequest(facility, dimensionCode);
    var operationId = InternalId.generate();
    var correlationId = CorrelationId.generate();
    var scope = facility.toExternalReference();
    var fingerprint =
        FederationOperationFingerprint.calculate(
            localDeployment, peer.peerDeploymentId(), scope, OPERATION, body);
    var request =
        new FederationPeerOperationRequest(
            peer.partnerKey(),
            localDeployment,
            peer.peerDeploymentId(),
            scope,
            OPERATION,
            operationId,
            peer.configurationVersion(),
            body,
            fingerprint,
            java.util.Optional.of(correlationId));
    PartnerOutboundOperationResponse response;
    try {
      response = peerOperations.execute(request);
    } catch (FederationPeerOperationException failure) {
      throw mapFailure(failure.failure());
    }
    if (response.configurationVersion() != peer.configurationVersion()
        || response.body().length == 0
        || response.body().length > MAX_RESPONSE_BYTES
        || response.contentType().isEmpty()
        || !response
            .contentType()
            .orElseThrow()
            .toLowerCase(java.util.Locale.ROOT)
            .startsWith("application/json")) {
      throw invalidResponse();
    }
    return parseResponse(response.body(), facility, dimensionCode);
  }

  private byte[] encodeRequest(RemoteFacilityReference facility, String dimensionCode) {
    ObjectNode document = objectMapper.createObjectNode();
    document.put("contractVersion", CONTRACT_VERSION);
    document.put("deploymentId", facility.deploymentId().value());
    document.put("context", facility.context());
    document.put("resourceId", facility.resourceId().toString());
    document.put("dimensionCode", dimensionCode);
    try {
      return objectMapper.writeValueAsBytes(document);
    } catch (JacksonException invalid) {
      throw invalidResponse();
    }
  }

  private RemoteCapacityEvidence parseResponse(
      byte[] bytes, RemoteFacilityReference expectedFacility, String expectedDimension) {
    try {
      String utf8 = decodeUtf8(bytes);
      JsonNode document = objectMapper.readTree(utf8);
      if (document == null
          || !document.isObject()
          || document.properties().stream()
              .anyMatch(field -> !RESPONSE_FIELDS.contains(field.getKey()))) {
        throw invalidResponse();
      }
      requireExactText(document, "contractVersion", CONTRACT_VERSION);
      RemoteFacilityReference actualFacility =
          new RemoteFacilityReference(
              new org.ayuvahak.platform.federation.domain.DeploymentId(
                  requiredText(document, "deploymentId")),
              requiredText(document, "context"),
              InternalId.parse(requiredText(document, "resourceId")));
      String dimensionCode = requiredText(document, "dimensionCode");
      if (!actualFacility.equals(expectedFacility) || !dimensionCode.equals(expectedDimension)) {
        throw invalidResponse();
      }
      JsonNode quantityNode = document.get("quantity");
      java.util.Optional<BigDecimal> quantity =
          quantityNode == null || quantityNode.isNull()
              ? java.util.Optional.empty()
              : number(quantityNode);
      return new RemoteCapacityEvidence(
          actualFacility,
          dimensionCode,
          requiredPositiveLong(document, "sourceSequence"),
          requiredPositiveLong(document, "dimensionVersion"),
          requiredPositiveLong(document, "policyVersion"),
          requiredText(document, "sourceProfileVersion"),
          requiredText(document, "evidenceId"),
          RemoteCapacityOutcome.valueOf(requiredText(document, "outcome")),
          RemoteEvidenceFreshness.valueOf(requiredText(document, "freshness")),
          requiredBoolean(document, "complete"),
          quantity,
          optionalText(document, "state"),
          optionalText(document, "unit"),
          Instant.parse(requiredText(document, "observedAt")),
          clock.instant(),
          Instant.parse(requiredText(document, "validUntil")),
          false);
    } catch (JacksonException | IllegalArgumentException malformed) {
      throw invalidResponse();
    }
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException invalid) {
      throw invalidResponse();
    }
  }

  private static String requiredText(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual() || value.asString().isBlank()) {
      throw invalidResponse();
    }
    return value.asString();
  }

  private static void requireExactText(JsonNode object, String field, String expected) {
    if (!expected.equals(requiredText(object, field))) {
      throw invalidResponse();
    }
  }

  private static long requiredPositiveLong(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalidResponse();
    }
    long result = value.longValue();
    if (result < 1) {
      throw invalidResponse();
    }
    return result;
  }

  private static boolean requiredBoolean(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isBoolean()) {
      throw invalidResponse();
    }
    return value.booleanValue();
  }

  private static java.util.Optional<BigDecimal> number(JsonNode value) {
    if (!value.isNumber()) {
      throw invalidResponse();
    }
    return java.util.Optional.of(value.decimalValue());
  }

  private static java.util.Optional<String> optionalText(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || value.isNull()) {
      return java.util.Optional.empty();
    }
    if (!value.isTextual()) {
      throw invalidResponse();
    }
    return java.util.Optional.of(value.asString());
  }

  private static RemoteCapacityEvidenceExchangeException mapFailure(
      FederationPeerOperationFailure failure) {
    var reason =
        switch (failure) {
          case TRUST_DENIED -> RemoteCapacityEvidenceExchangeException.Reason.TRUST_DENIED;
          case TRUST_UNAVAILABLE, DESTINATION_NOT_REACHED ->
              RemoteCapacityEvidenceExchangeException.Reason.TRUST_UNAVAILABLE;
          case AUTHENTICATION_FAILED ->
              RemoteCapacityEvidenceExchangeException.Reason.AUTHENTICATION_FAILED;
          case REMOTE_REJECTED -> RemoteCapacityEvidenceExchangeException.Reason.REMOTE_REJECTED;
          case REMOTE_NOT_FOUND -> RemoteCapacityEvidenceExchangeException.Reason.REMOTE_REJECTED;
          case RESPONSE_UNCERTAIN -> RemoteCapacityEvidenceExchangeException.Reason.OUTCOME_UNKNOWN;
        };
    return new RemoteCapacityEvidenceExchangeException(reason);
  }

  private static RemoteCapacityEvidenceExchangeException invalidResponse() {
    return new RemoteCapacityEvidenceExchangeException(
        RemoteCapacityEvidenceExchangeException.Reason.INVALID_RESPONSE);
  }

  private static void requireDimension(String dimensionCode) {
    if (dimensionCode == null || !dimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("dimension code must be a stable identifier");
    }
  }
}
