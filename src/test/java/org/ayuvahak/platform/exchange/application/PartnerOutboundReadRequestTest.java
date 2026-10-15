package org.ayuvahak.platform.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.junit.jupiter.api.Test;

class PartnerOutboundReadRequestTest {

  private static final PartnerTrustConfiguration.AllowedOperation DIRECTORY_SEARCH =
      new PartnerTrustConfiguration.AllowedOperation(
          PartnerTrustConfiguration.OwnerContext.DIRECTORY,
          "mcsd.iti90.search",
          PartnerTrustConfiguration.Direction.OUTBOUND,
          PartnerTrustConfiguration.Protocol.FHIR_R4_JSON,
          "IHE-mCSD");

  @Test
  void redactsQueryValuesFromDiagnosticRepresentation() {
    PartnerOutboundReadRequest request =
        new PartnerOutboundReadRequest(
            new PartnerKey("SYNTHETIC-DIRECTORY"),
            DIRECTORY_SEARCH,
            List.of("Organization"),
            Map.of("identifier", List.of("SYN-PRIVATE-MARKER")));

    assertThat(request.toString()).contains("SYNTHETIC-DIRECTORY", "mcsd.iti90.search");
    assertThat(request.toString()).doesNotContain("SYN-PRIVATE-MARKER");
  }

  @Test
  void rejectsUrlAndTraversalSegmentsBeforeTransport() {
    assertThatThrownBy(
            () ->
                new PartnerOutboundReadRequest(
                    new PartnerKey("SYNTHETIC-DIRECTORY"),
                    DIRECTORY_SEARCH,
                    List.of("https://attacker.example"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PartnerOutboundReadRequest(
                    new PartnerKey("SYNTHETIC-DIRECTORY"),
                    DIRECTORY_SEARCH,
                    List.of("..", "outside"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInboundOperationAtOutboundReadBoundary() {
    PartnerTrustConfiguration.AllowedOperation inboundFeed =
        new PartnerTrustConfiguration.AllowedOperation(
            PartnerTrustConfiguration.OwnerContext.DIRECTORY,
            "mcsd.iti130.feed",
            PartnerTrustConfiguration.Direction.INBOUND,
            PartnerTrustConfiguration.Protocol.FHIR_R4_JSON,
            "IHE-mCSD");

    assertThatThrownBy(
            () ->
                new PartnerOutboundReadRequest(
                    new PartnerKey("SYNTHETIC-DIRECTORY"),
                    inboundFeed,
                    List.of("Organization"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void leavesOwnerSpecificOperationShapeToTheOwningAdapter() {
    PartnerTrustConfiguration.AllowedOperation ipsRead =
        new PartnerTrustConfiguration.AllowedOperation(
            PartnerTrustConfiguration.OwnerContext.REFERRAL,
            "ips.patient-context.read",
            PartnerTrustConfiguration.Direction.OUTBOUND,
            PartnerTrustConfiguration.Protocol.FHIR_R4_JSON,
            "IPS-2.0.1");

    PartnerOutboundReadRequest request =
        new PartnerOutboundReadRequest(
            new PartnerKey("SYNTHETIC-CLINICAL-SOURCE"),
            ipsRead,
            List.of("Patient", "SYN-PATIENT-001"),
            Map.of("_id", List.of("SYN-PATIENT-001")),
            Optional.empty());

    assertThat(request.pathSegments()).containsExactly("Patient", "SYN-PATIENT-001");
    assertThat(request.queryParameters()).containsKey("_id");
  }
}
