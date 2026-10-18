package org.ayuvahak.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion.Direction;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;

class FederatedAggregateMappingVersionTest {

  private static final DeploymentId PEER = new DeploymentId("SYN-DEPLOYMENT-B");
  private static final String DEFINITION_FINGERPRINT = "a".repeat(64);
  private static final ExternalReference LOCAL_ORGANIZATION =
      new ExternalReference(new IssuerId("urn:ayuvahak:directory"), "Organization", "SYN-ORG-001");
  private static final ExternalReference REMOTE_ORGANIZATION =
      new ExternalReference(
          new IssuerId("urn:ayuvahak:deployment:SYN-DEPLOYMENT-B:directory"),
          "Organization",
          "SYN-ORG-002");

  @Test
  void outboundMappingBindsSemanticVersionPeerConfigurationAndDisclosureKeys() {
    FederatedAggregateMappingVersion mapping = outbound();

    assertThat(mapping.direction()).isEqualTo(Direction.OUTBOUND);
    assertThat(mapping.definitionFingerprint()).isEqualTo(DEFINITION_FINGERPRINT);
    assertThat(mapping.administrativeOrganization()).isEqualTo(LOCAL_ORGANIZATION);
    assertThat(mapping.fingerprint()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(mapping.effectiveAt(Instant.parse("2026-05-01T00:00:00Z"))).isTrue();
    assertThat(mapping.effectiveAt(Instant.parse("2027-01-01T00:00:00Z"))).isFalse();
    assertThat(mapping.toString()).doesNotContain("SYN-ORG-001", "SYN-ORG-002", "SYN-REPORTING");
  }

  @Test
  void rejectsScopeWhoseNamespaceDoesNotMatchDirectionAndPeer() {
    assertThatThrownBy(
            () ->
                new FederatedAggregateMappingVersion(
                    "SYN-MAPPING-001",
                    1,
                    Direction.OUTBOUND,
                    "SYN-INDICATOR-001",
                    1,
                    DEFINITION_FINGERPRINT,
                    LOCAL_ORGANIZATION,
                    new PartnerKey("synthetic-peer-b"),
                    PEER,
                    1,
                    new ExternalReference(
                        new IssuerId("urn:ayuvahak:directory"), "Organization", "SYN-ORG-002"),
                    "SYN-REPORTING-SCOPE-A",
                    "SYN-REPORTING-CELL-A",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inboundMappingRequiresRemoteSourceAndLocalRecipientNamespace() {
    FederatedAggregateMappingVersion inbound =
        new FederatedAggregateMappingVersion(
            "SYN-MAPPING-IN-001",
            1,
            Direction.INBOUND,
            "SYN-INDICATOR-001",
            1,
            DEFINITION_FINGERPRINT,
            REMOTE_ORGANIZATION,
            new PartnerKey("synthetic-peer-b"),
            PEER,
            1,
            LOCAL_ORGANIZATION,
            "SYN-REPORTING-SCOPE-A",
            "SYN-REPORTING-CELL-A",
            Instant.parse("2026-01-01T00:00:00Z"),
            Optional.empty());

    assertThat(inbound.direction()).isEqualTo(Direction.INBOUND);
    assertThat(inbound.administrativeOrganization()).isEqualTo(LOCAL_ORGANIZATION);
  }

  private static FederatedAggregateMappingVersion outbound() {
    return new FederatedAggregateMappingVersion(
        "SYN-MAPPING-OUT-001",
        1,
        Direction.OUTBOUND,
        "SYN-INDICATOR-001",
        1,
        DEFINITION_FINGERPRINT,
        LOCAL_ORGANIZATION,
        new PartnerKey("synthetic-peer-b"),
        PEER,
        1,
        REMOTE_ORGANIZATION,
        "SYN-REPORTING-SCOPE-A",
        "SYN-REPORTING-CELL-A",
        Instant.parse("2026-01-01T00:00:00Z"),
        Optional.of(Instant.parse("2026-12-31T00:00:00Z")));
  }
}
