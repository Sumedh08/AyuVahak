package org.ayuvahak.matching.adapter.configuration;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.matching.application.FederatedCapacityPeerPolicy;
import org.ayuvahak.matching.application.RemoteCapacityPeerTarget;
import org.ayuvahak.matching.domain.RemoteCapacityEvidenceCompatibility;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Administrator-configured logical peer and source-version policy; it contains no URL/secrets. */
@ConfigurationProperties("ayuvahak.matching.federation")
public record MatchingFederationPeerProperties(List<CapacityPeer> capacityPeers)
    implements FederatedCapacityPeerPolicy {

  public MatchingFederationPeerProperties {
    capacityPeers = List.copyOf(Objects.requireNonNullElse(capacityPeers, List.of()));
    if (capacityPeers.stream().map(CapacityPeer::peerDeploymentId).distinct().count()
        != capacityPeers.size()) {
      throw new IllegalArgumentException("federated Capacity peer deployments must be unique");
    }
  }

  @Override
  public Optional<RemoteCapacityPeerTarget> resolve(
      RemoteFacilityReference facility, String dimensionCode) {
    Objects.requireNonNull(facility, "remote Facility reference");
    requireDimension(dimensionCode);
    return capacityPeers.stream()
        .filter(peer -> peer.peerDeploymentId().equals(facility.deploymentId().value()))
        .findFirst()
        .flatMap(
            peer ->
                peer.dimensions().stream()
                    .filter(dimension -> dimension.dimensionCode().equals(dimensionCode))
                    .findFirst()
                    .map(
                        dimension ->
                            new RemoteCapacityPeerTarget(
                                new PartnerKey(peer.partnerKey()),
                                new DeploymentId(peer.peerDeploymentId()),
                                peer.configurationVersion(),
                                new RemoteCapacityEvidenceCompatibility(
                                    dimension.sourceProfileVersion(),
                                    dimension.dimensionVersion(),
                                    dimension.policyVersion()))));
  }

  public record CapacityPeer(
      String peerDeploymentId,
      String partnerKey,
      int configurationVersion,
      List<DimensionCompatibility> dimensions) {

    public CapacityPeer {
      new DeploymentId(peerDeploymentId);
      new PartnerKey(partnerKey);
      if (configurationVersion < 1) {
        throw new IllegalArgumentException("partner configuration version must be positive");
      }
      dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
      if (dimensions.isEmpty()
          || new HashSet<>(dimensions.stream().map(DimensionCompatibility::dimensionCode).toList())
                  .size()
              != dimensions.size()) {
        throw new IllegalArgumentException("peer dimensions must be nonempty and unique");
      }
    }
  }

  public record DimensionCompatibility(
      String dimensionCode,
      String sourceProfileVersion,
      long dimensionVersion,
      long policyVersion) {

    public DimensionCompatibility {
      requireDimension(dimensionCode);
      new RemoteCapacityEvidenceCompatibility(
          sourceProfileVersion, dimensionVersion, policyVersion);
    }
  }

  private static void requireDimension(String dimensionCode) {
    if (dimensionCode == null || !dimensionCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("dimension code must be a stable identifier");
    }
  }
}
