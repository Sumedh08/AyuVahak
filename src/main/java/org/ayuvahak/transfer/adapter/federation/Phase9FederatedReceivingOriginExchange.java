package org.ayuvahak.transfer.adapter.federation;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationException;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationFailure;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationPort;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationRequest;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.transfer.application.FederatedReceivingOriginExchange;
import org.ayuvahak.transfer.application.FederatedReceivingOriginExchangeException;
import org.ayuvahak.transfer.application.FederatedReceivingOriginExchangeResult;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.AssessmentOutcome;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.RemoteAssessmentEvidence;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.RemoteReservationStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Sends the fixed Transfer receiving contract through the Phase 9 trusted exchange port. */
@Component
public final class Phase9FederatedReceivingOriginExchange
    implements FederatedReceivingOriginExchange {

  private static final int MAX_RESPONSE_BYTES = 65_536;
  private static final String PROFILE = "AyuVahak-Federation-v1";
  private static final Set<String> RESPONSE_FIELDS =
      Set.of(
          "protocolProfile",
          "sourceDeploymentId",
          "operationId",
          "requestFingerprint",
          "outcome",
          "reservationStatus",
          "receivingRequestId",
          "capacityReservationId",
          "clinicalAssessment",
          "operationalAssessment",
          "acceptedAt",
          "sourceUpdatedAt",
          "receivingVersion");
  private static final Set<String> ASSESSMENT_FIELDS =
      Set.of("outcome", "reasonCode", "occurredAt", "recordedAt");

  private final FederationPeerOperationPort exchange;
  private final org.ayuvahak.matching.application.FederationLocalDeploymentIdentity deployment;
  private final ObjectMapper mapper;

  public Phase9FederatedReceivingOriginExchange(
      FederationPeerOperationPort exchange,
      org.ayuvahak.matching.application.FederationLocalDeploymentIdentity deployment,
      ObjectMapper mapper) {
    this.exchange = java.util.Objects.requireNonNull(exchange, "Phase 9 peer exchange");
    this.deployment = java.util.Objects.requireNonNull(deployment, "deployment identity");
    this.mapper = java.util.Objects.requireNonNull(mapper, "JSON mapper");
  }

  @Override
  public FederatedReceivingOriginExchangeResult execute(
      FederatedOriginReceivingAttempt attempt, boolean statusOnly) {
    var origin =
        deployment.current().orElseThrow(FederatedReceivingOriginExchangeException::notSent);
    if (!origin.equals(attempt.originDeployment())) {
      throw FederatedReceivingOriginExchangeException.notSent();
    }
    AllowedOperation operation =
        statusOnly
            ? AllowedOperation.transferRemoteReservationStatus(Direction.OUTBOUND)
            : AllowedOperation.transferRemoteReceivingRequest(Direction.OUTBOUND);
    byte[] body = statusOnly ? statusRequest(attempt) : attempt.requestBody();
    var scope =
        new org.ayuvahak.platform.core.ExternalReference(
            new org.ayuvahak.platform.core.IssuerId(
                "urn:ayuvahak:deployment:" + attempt.peerDeployment().value() + ":directory"),
            "Facility",
            requiredDestinationFacilityId(attempt.requestBody()));
    var fingerprint =
        FederationOperationFingerprint.calculate(
            origin, attempt.peerDeployment(), scope, operation, body);
    try {
      var response =
          exchange.execute(
              new FederationPeerOperationRequest(
                  attempt.partnerKey(),
                  origin,
                  attempt.peerDeployment(),
                  scope,
                  operation,
                  attempt.operationId(),
                  attempt.configurationVersion(),
                  body,
                  fingerprint,
                  java.util.Optional.of(CorrelationId.generate())));
      if (response.configurationVersion() != attempt.configurationVersion()
          || response.body().length == 0
          || response.body().length > MAX_RESPONSE_BYTES
          || response.contentType().isEmpty()
          || !response
              .contentType()
              .orElseThrow()
              .toLowerCase(java.util.Locale.ROOT)
              .startsWith("application/json")) {
        throw FederatedReceivingOriginExchangeException.invalidResponse();
      }
      return parse(response.body(), attempt);
    } catch (FederationPeerOperationException failure) {
      throw map(failure.failure(), statusOnly);
    }
  }

  private byte[] statusRequest(FederatedOriginReceivingAttempt attempt) {
    ObjectNode request = mapper.createObjectNode();
    request.put("protocolProfile", PROFILE);
    request.put("sourceDeploymentId", attempt.originDeployment().value());
    request.put("operationId", attempt.operationId().toString());
    request.put("requestFingerprint", attempt.requestFingerprint());
    try {
      JsonNode original = mapper.readTree(attempt.requestBody());
      JsonNode destinationOrganization =
          original == null ? null : original.get("destinationOrganization");
      JsonNode destinationFacility = original == null ? null : original.get("destinationFacility");
      if (destinationOrganization == null
          || !destinationOrganization.isObject()
          || destinationFacility == null
          || !destinationFacility.isObject()) {
        throw FederatedReceivingOriginExchangeException.invalidResponse();
      }
      request.set("destinationOrganization", destinationOrganization.deepCopy());
      request.set("destinationFacility", destinationFacility.deepCopy());
      return mapper.writeValueAsBytes(request);
    } catch (JacksonException failure) {
      throw FederatedReceivingOriginExchangeException.invalidResponse();
    }
  }

  private FederatedReceivingOriginExchangeResult parse(
      byte[] bytes, FederatedOriginReceivingAttempt attempt) {
    try {
      JsonNode response =
          mapper.readTree(
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                  .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                  .decode(java.nio.ByteBuffer.wrap(bytes))
                  .toString());
      if (response == null
          || !response.isObject()
          || response.properties().stream()
              .anyMatch(field -> !RESPONSE_FIELDS.contains(field.getKey()))
          || !PROFILE.equals(text(response, "protocolProfile"))
          || !attempt.peerDeployment().value().equals(text(response, "sourceDeploymentId"))
          || !attempt.operationId().toString().equals(text(response, "operationId"))
          || !attempt.requestFingerprint().equals(text(response, "requestFingerprint"))) {
        throw FederatedReceivingOriginExchangeException.invalidResponse();
      }
      long receivingVersion = positiveLong(response, "receivingVersion");
      var outcome =
          FederatedOriginReceivingAttempt.RemoteOutcome.valueOf(text(response, "outcome"));
      var reservationStatus = RemoteReservationStatus.valueOf(text(response, "reservationStatus"));
      var receivingRequestId = InternalId.parse(text(response, "receivingRequestId"));
      var reservationValue = response.get("capacityReservationId");
      java.util.Optional<org.ayuvahak.platform.core.InternalId> reservation =
          reservationValue == null || reservationValue.isNull()
              ? java.util.Optional.empty()
              : java.util.Optional.of(InternalId.parse(asText(reservationValue)));
      var clinical = assessment(response.get("clinicalAssessment"));
      var operational = assessment(response.get("operationalAssessment"));
      var acceptedValue = response.get("acceptedAt");
      var acceptedAt =
          acceptedValue == null || acceptedValue.isNull()
              ? java.util.Optional.<java.time.Instant>empty()
              : java.util.Optional.of(java.time.Instant.parse(asText(acceptedValue)));
      var sourceUpdatedAt = java.time.Instant.parse(text(response, "sourceUpdatedAt"));
      return new FederatedReceivingOriginExchangeResult(
          outcome,
          reservationStatus,
          receivingRequestId,
          reservation,
          clinical,
          operational,
          acceptedAt,
          sourceUpdatedAt,
          receivingVersion);
    } catch (JacksonException
        | java.nio.charset.CharacterCodingException
        | IllegalArgumentException malformed) {
      throw FederatedReceivingOriginExchangeException.invalidResponse();
    }
  }

  private java.util.Optional<RemoteAssessmentEvidence> assessment(JsonNode value) {
    if (value == null || value.isNull()) {
      return java.util.Optional.empty();
    }
    if (!value.isObject()
        || value.properties().stream()
            .anyMatch(field -> !ASSESSMENT_FIELDS.contains(field.getKey()))
        || value.size() != ASSESSMENT_FIELDS.size()) {
      throw FederatedReceivingOriginExchangeException.invalidResponse();
    }
    try {
      return java.util.Optional.of(
          new RemoteAssessmentEvidence(
              AssessmentOutcome.valueOf(text(value, "outcome")),
              text(value, "reasonCode"),
              java.time.Instant.parse(text(value, "occurredAt")),
              java.time.Instant.parse(text(value, "recordedAt"))));
    } catch (IllegalArgumentException invalid) {
      throw FederatedReceivingOriginExchangeException.invalidResponse();
    }
  }

  private static String requiredDestinationFacilityId(byte[] body) {
    try {
      JsonNode root = new ObjectMapper().readTree(new String(body, StandardCharsets.UTF_8));
      JsonNode facility = root == null ? null : root.get("destinationFacility");
      JsonNode value = facility == null ? null : facility.get("value");
      return asText(value);
    } catch (JacksonException | IllegalArgumentException malformed) {
      throw FederatedReceivingOriginExchangeException.invalidResponse();
    }
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object.get(field);
    return asText(value);
  }

  private static String asText(JsonNode value) {
    if (value == null || !value.isTextual() || value.asString().isBlank()) {
      throw FederatedReceivingOriginExchangeException.invalidResponse();
    }
    return value.asString();
  }

  private static long positiveLong(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw FederatedReceivingOriginExchangeException.invalidResponse();
    }
    long result = value.longValue();
    if (result < 1) {
      throw FederatedReceivingOriginExchangeException.invalidResponse();
    }
    return result;
  }

  private static FederatedReceivingOriginExchangeException map(
      FederationPeerOperationFailure failure, boolean statusOnly) {
    return switch (failure) {
      case TRUST_DENIED, TRUST_UNAVAILABLE, AUTHENTICATION_FAILED, DESTINATION_NOT_REACHED ->
          FederatedReceivingOriginExchangeException.notSent();
      case REMOTE_REJECTED -> FederatedReceivingOriginExchangeException.invalidResponse();
      case REMOTE_NOT_FOUND ->
          statusOnly
              ? FederatedReceivingOriginExchangeException.notSent()
              : FederatedReceivingOriginExchangeException.invalidResponse();
      case RESPONSE_UNCERTAIN -> FederatedReceivingOriginExchangeException.outcomeUnknown();
    };
  }
}
