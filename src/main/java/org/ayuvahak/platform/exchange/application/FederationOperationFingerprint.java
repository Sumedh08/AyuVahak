package org.ayuvahak.platform.exchange.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Stable digest of a bounded peer operation's destination, scope, named contract, and payload. */
public record FederationOperationFingerprint(String value) {

  public FederationOperationFingerprint {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "federation fingerprint must be a lowercase SHA-256 digest");
    }
  }

  public static FederationOperationFingerprint calculate(
      DeploymentId sourceDeployment,
      DeploymentId peerDeployment,
      ExternalReference scope,
      AllowedOperation operation,
      byte[] payload) {
    Objects.requireNonNull(sourceDeployment, "sourceDeployment");
    Objects.requireNonNull(peerDeployment, "peerDeployment");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(payload, "payload");
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream fields = new DataOutputStream(bytes)) {
        // PartnerKey is an administrator-local alias; deployments namespace the operation.
        write(fields, sourceDeployment.value());
        write(fields, peerDeployment.value());
        write(fields, scope.issuer().value());
        write(fields, scope.resourceType());
        write(fields, scope.value());
        write(fields, operation.ownerContext().name());
        write(fields, operation.operationCode());
        write(fields, operation.protocol().name());
        write(fields, operation.profile());
        fields.writeInt(payload.length);
        fields.write(payload);
      }
      return new FederationOperationFingerprint(
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Unable to fingerprint the federation operation.", impossible);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }

  private static void write(DataOutputStream fields, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    fields.writeInt(encoded.length);
    fields.write(encoded);
  }

  @Override
  public String toString() {
    return "FederationOperationFingerprint[redacted]";
  }
}
