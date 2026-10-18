package org.ayuvahak.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Durable technical attempt for one source snapshot and one explicitly trusted peer. */
public record FederatedAggregatePublication(
    UUID operationId,
    String operationFingerprint,
    DeploymentId sourceDeployment,
    DeploymentId peerDeployment,
    UUID sourceSnapshotId,
    UUID revisionSeriesId,
    int revisionNumber,
    String mappingKey,
    int mappingVersion,
    PartnerKey partnerKey,
    int partnerConfigurationVersion,
    String definitionFingerprint,
    String contentFingerprint,
    Optional<UUID> disclosureReservationId,
    State state,
    String resultClass,
    Optional<UUID> receiptId,
    Instant createdAt,
    Instant updatedAt) {

  public FederatedAggregatePublication {
    Objects.requireNonNull(operationId, "operation id");
    if (operationId.version() != 4 || operationId.variant() != 2) {
      throw new IllegalArgumentException("federated aggregate operation ID must be UUIDv4");
    }
    requireFingerprint(operationFingerprint, "operation fingerprint");
    Objects.requireNonNull(sourceDeployment, "source deployment");
    Objects.requireNonNull(peerDeployment, "peer deployment");
    if (sourceDeployment.equals(peerDeployment)) {
      throw new IllegalArgumentException("aggregate peer must be another deployment");
    }
    Objects.requireNonNull(sourceSnapshotId, "source snapshot id");
    Objects.requireNonNull(revisionSeriesId, "revision series id");
    if (revisionNumber < 1 || mappingVersion < 1 || partnerConfigurationVersion < 1) {
      throw new IllegalArgumentException("aggregate publication versions must be positive");
    }
    if (mappingKey == null || !mappingKey.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
      throw new IllegalArgumentException("aggregate mapping key is invalid");
    }
    Objects.requireNonNull(partnerKey, "partner key");
    requireFingerprint(definitionFingerprint, "definition fingerprint");
    requireFingerprint(contentFingerprint, "content fingerprint");
    disclosureReservationId =
        Objects.requireNonNull(disclosureReservationId, "disclosure reservation id");
    Objects.requireNonNull(state, "publication state");
    if (resultClass == null || !resultClass.matches("[A-Z][A-Z0-9_]{0,47}")) {
      throw new IllegalArgumentException("publication result class is invalid");
    }
    receiptId = Objects.requireNonNull(receiptId, "receipt id");
    Objects.requireNonNull(createdAt, "created at");
    Objects.requireNonNull(updatedAt, "updated at");
  }

  private static void requireFingerprint(String value, String label) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be a lowercase SHA-256 digest");
    }
  }

  @Override
  public String toString() {
    return "FederatedAggregatePublication[operationId=redacted, peer="
        + peerDeployment
        + ", snapshot=redacted, state="
        + state
        + ", resultClass="
        + resultClass
        + "]";
  }

  public enum State {
    READY,
    SENDING,
    ACKNOWLEDGED,
    INCOMPATIBLE,
    OUTCOME_UNKNOWN,
    REJECTED,
    WITHHELD
  }
}
