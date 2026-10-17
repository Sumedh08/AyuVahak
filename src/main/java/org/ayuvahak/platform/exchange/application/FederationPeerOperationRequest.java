package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.domain.FederationPeerOperations;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Fixed-contract outbound request; callers provide logical identities, never network locations. */
public record FederationPeerOperationRequest(
    PartnerKey partnerKey,
    DeploymentId sourceDeployment,
    DeploymentId peerDeployment,
    ExternalReference scope,
    AllowedOperation operation,
    InternalId operationId,
    int expectedConfigurationVersion,
    byte[] body,
    FederationOperationFingerprint fingerprint,
    Optional<CorrelationId> correlationId) {

  public static final int MAX_REQUEST_BYTES = 1_048_576;

  public FederationPeerOperationRequest {
    Objects.requireNonNull(partnerKey, "partnerKey");
    Objects.requireNonNull(sourceDeployment, "sourceDeployment");
    Objects.requireNonNull(peerDeployment, "peerDeployment");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(fingerprint, "fingerprint");
    correlationId = Objects.requireNonNull(correlationId, "correlationId");
    if (sourceDeployment.equals(peerDeployment)) {
      throw new IllegalArgumentException("a federation operation must target another deployment");
    }
    if (!scope.resourceType().equals("Organization") && !scope.resourceType().equals("Facility")) {
      throw new IllegalArgumentException("federation scope must be an Organization or Facility");
    }
    if ("*".equals(scope.value())) {
      throw new IllegalArgumentException("wildcard federation scope is not permitted");
    }
    if (!FederationPeerOperations.supportsOutbound(operation)) {
      throw new IllegalArgumentException("federation operation is outside the named allowlist");
    }
    if (expectedConfigurationVersion < 1) {
      throw new IllegalArgumentException("expected partner configuration version must be positive");
    }
    body = Objects.requireNonNull(body, "body").clone();
    if (body.length == 0 || body.length > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException(
          "federation operation body is empty or exceeds its byte limit");
    }
    FederationOperationFingerprint expected =
        FederationOperationFingerprint.calculate(
            sourceDeployment, peerDeployment, scope, operation, body);
    if (!expected.equals(fingerprint)) {
      throw new IllegalArgumentException(
          "federation operation fingerprint does not match its content");
    }
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public String toString() {
    return "FederationPeerOperationRequest[operation="
        + operation.operationCode()
        + ", configurationVersion="
        + expectedConfigurationVersion
        + ", operationId=redacted, scope=redacted, body=redacted]";
  }
}
