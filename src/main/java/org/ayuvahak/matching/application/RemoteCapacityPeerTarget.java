package org.ayuvahak.matching.application;

import java.util.Objects;
import org.ayuvahak.matching.domain.RemoteCapacityEvidenceCompatibility;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Resolved administrator-approved peer binding for one named remote Capacity request. */
public record RemoteCapacityPeerTarget(
    PartnerKey partnerKey,
    DeploymentId peerDeploymentId,
    int configurationVersion,
    RemoteCapacityEvidenceCompatibility compatibility) {

  public RemoteCapacityPeerTarget {
    Objects.requireNonNull(partnerKey, "partner key");
    Objects.requireNonNull(peerDeploymentId, "peer deployment ID");
    if (configurationVersion < 1) {
      throw new IllegalArgumentException("partner configuration version must be positive");
    }
    Objects.requireNonNull(compatibility, "source compatibility");
  }
}
