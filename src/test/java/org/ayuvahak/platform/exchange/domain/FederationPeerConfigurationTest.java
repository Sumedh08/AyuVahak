package org.ayuvahak.platform.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;

class FederationPeerConfigurationTest {

  private static final ExternalReference ORGANIZATION =
      new ExternalReference(new IssuerId("urn:synthetic:peer"), "Organization", "SYN-ORG-001");
  private static final ExternalReference FACILITY_A =
      new ExternalReference(new IssuerId("urn:synthetic:peer"), "Facility", "SYN-FACILITY-A");
  private static final ExternalReference FACILITY_B =
      new ExternalReference(new IssuerId("urn:synthetic:peer"), "Facility", "SYN-FACILITY-B");

  @Test
  void permitsOnlyItsNamespacedOrganizationAndExplicitFacilities() {
    FederationPeerConfiguration peer =
        new FederationPeerConfiguration(
            new DeploymentId("SYN-DEPLOYMENT-B"),
            ORGANIZATION,
            "AyuVahak-Federation-v1",
            List.of(FACILITY_A));

    assertThat(peer.permits(ORGANIZATION, ORGANIZATION)).isTrue();
    assertThat(peer.permits(ORGANIZATION, FACILITY_A)).isTrue();
    assertThat(peer.permits(ORGANIZATION, FACILITY_B)).isFalse();
    assertThat(
            peer.permits(
                new ExternalReference(
                    new IssuerId("urn:synthetic:other"), "Organization", "SYN-ORG-001"),
                ORGANIZATION))
        .isFalse();
  }

  @Test
  void rejectsWildcardAndNonFacilityScope() {
    assertThatThrownBy(
            () ->
                new FederationPeerConfiguration(
                    new DeploymentId("SYN-DEPLOYMENT-B"),
                    ORGANIZATION,
                    "AyuVahak-Federation-v1",
                    List.of(
                        new ExternalReference(
                            new IssuerId("urn:synthetic:peer"), "Facility", "*"))))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new FederationPeerConfiguration(
                    new DeploymentId("SYN-DEPLOYMENT-B"),
                    ORGANIZATION,
                    "AyuVahak-Federation-v1",
                    List.of(ORGANIZATION)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deploymentIdentityIsAStableOpaqueNamespaceNotAUrlOrOrganizationIdentifier() {
    assertThat(new DeploymentId("SYN-DEPLOYMENT-A").value()).isEqualTo("SYN-DEPLOYMENT-A");
    assertThatThrownBy(() -> new DeploymentId("https://peer.example.test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DeploymentId("SYN-DEPLOYMENT-A "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
