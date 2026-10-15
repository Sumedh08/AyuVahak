package org.ayuvahak.platform.exchange.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadRequest;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.junit.jupiter.api.Test;

class PartnerRequestUriBuilderTest {

  private static final PartnerKey PARTNER = new PartnerKey("synthetic-directory");
  private static final PartnerTrustConfiguration.AllowedOperation OPERATION =
      new PartnerTrustConfiguration.AllowedOperation(
          PartnerTrustConfiguration.OwnerContext.DIRECTORY,
          "mcsd.iti90.search",
          PartnerTrustConfiguration.Direction.OUTBOUND,
          PartnerTrustConfiguration.Protocol.FHIR_R4_JSON,
          "IHE-mCSD");

  @Test
  void buildsOnlyUnderConfiguredOriginAndEncodesQueryValues() {
    PartnerTrustConfiguration profile = configuration("https://partner.example", "/fhir");
    PartnerOutboundReadRequest request =
        new PartnerOutboundReadRequest(
            PARTNER,
            OPERATION,
            List.of("Organization"),
            Map.of("identifier", List.of("urn:synthetic:directory|SYN-001 & more")));

    var uri = PartnerRequestUriBuilder.build(profile, request);

    assertThat(uri.toASCIIString())
        .isEqualTo(
            "https://partner.example/fhir/Organization?identifier=urn%3Asynthetic%3Adirectory%7CSYN-001%20%26%20more");
  }

  @Test
  void refusesOperationNotPresentInPartnerConfiguration() {
    PartnerTrustConfiguration profile = configuration("https://partner.example", "/fhir");
    PartnerTrustConfiguration.AllowedOperation differentOperation =
        new PartnerTrustConfiguration.AllowedOperation(
            PartnerTrustConfiguration.OwnerContext.REFERRAL,
            "ips.patient-context.read",
            PartnerTrustConfiguration.Direction.OUTBOUND,
            PartnerTrustConfiguration.Protocol.FHIR_R4_JSON,
            "IPS-2.0.1");
    PartnerOutboundReadRequest request =
        new PartnerOutboundReadRequest(
            PARTNER,
            differentOperation,
            List.of("Patient", "$summary"),
            Map.of(
                "identifier", List.of("urn:synthetic:patient|SYN-001"),
                "profile",
                    List.of(
                        "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips|2.0.1")));

    assertThatThrownBy(() -> PartnerRequestUriBuilder.build(profile, request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void buildsSystemSearchUnderTheConfiguredBaseAndKeepsOnlyBoundedPageParameters() {
    PartnerTrustConfiguration profile = configuration("https://partner.example", "/fhir");
    PartnerOutboundReadRequest request =
        new PartnerOutboundReadRequest(
            PARTNER,
            OPERATION,
            List.of(),
            Map.of("_count", List.of("200"), "_type", List.of("Organization,Location")),
            Optional.of(
                "https://partner.example/fhir?_getpages=synthetic-page&_getpagesoffset=20&_type=Patient"));

    var uri = PartnerRequestUriBuilder.build(profile, request);

    assertThat(uri.getHost()).isEqualTo("partner.example");
    assertThat(uri.getPath()).isEqualTo("/fhir");
    assertThat(uri.getRawQuery())
        .isEqualTo(
            "_count=200&_getpages=synthetic-page&_getpagesoffset=20&_type=Organization%2CLocation");
  }

  @Test
  void buildsConfiguredIpsSummaryReadFromOpaqueIdentifierWithoutUsingItAsDestination() {
    PartnerTrustConfiguration profile = configuration("https://partner.example", "/fhir");
    PartnerTrustConfiguration.AllowedOperation ipsOperation =
        new PartnerTrustConfiguration.AllowedOperation(
            PartnerTrustConfiguration.OwnerContext.REFERRAL,
            "ips.patient-context.read",
            PartnerTrustConfiguration.Direction.OUTBOUND,
            PartnerTrustConfiguration.Protocol.FHIR_R4_JSON,
            "IPS-2.0.1");
    PartnerTrustConfiguration ipsProfile =
        new PartnerTrustConfiguration(
            PARTNER,
            profile.organization(),
            profile.endpointOrigin(),
            profile.basePath(),
            profile.authentication(),
            profile.tlsPolicy(),
            profile.tlsTrustReference(),
            profile.limits(),
            profile.retryClass(),
            List.of(ipsOperation));
    PartnerOutboundReadRequest request =
        new PartnerOutboundReadRequest(
            PARTNER,
            ipsOperation,
            List.of("Patient", "$summary"),
            Map.of(
                "identifier", List.of("urn:synthetic:issuer|SYN-PATIENT-001"),
                "profile",
                    List.of(
                        "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips|2.0.1")));

    var uri = PartnerRequestUriBuilder.build(ipsProfile, request);

    assertThat(uri.getHost()).isEqualTo("partner.example");
    assertThat(uri.getRawPath()).isEqualTo("/fhir/Patient/$summary");
    assertThat(uri.getRawQuery())
        .isEqualTo(
            "identifier=urn%3Asynthetic%3Aissuer%7CSYN-PATIENT-001&profile=http%3A%2F%2Fhl7.org%2Ffhir%2Fuv%2Fips%2FStructureDefinition%2FComposition-uv-ips%7C2.0.1");
  }

  @Test
  void rejectsContinuationThatChangesConfiguredOriginPathOrPagingMechanism() {
    PartnerTrustConfiguration profile = configuration("https://partner.example", "/fhir");
    PartnerOutboundReadRequest request =
        new PartnerOutboundReadRequest(
            PARTNER,
            OPERATION,
            List.of(),
            Map.of("_type", List.of("Organization")),
            Optional.of("/other?_getpages=synthetic-page"));

    assertThatThrownBy(() -> PartnerRequestUriBuilder.build(profile, request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsContinuationWithAnUntrustedOriginEvenWhenThePathMatches() {
    PartnerTrustConfiguration profile = configuration("https://partner.example", "/fhir");
    PartnerOutboundReadRequest request =
        new PartnerOutboundReadRequest(
            PARTNER,
            OPERATION,
            List.of(),
            Map.of("_type", List.of("Organization")),
            Optional.of("https://attacker.example/fhir?_getpages=synthetic-page"));

    assertThatThrownBy(() -> PartnerRequestUriBuilder.build(profile, request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PartnerTrustConfiguration configuration(String origin, String path) {
    return new PartnerTrustConfiguration(
        PARTNER,
        new ExternalReference(
            new IssuerId("urn:synthetic:directory"), "Organization", "SYN-ORG-001"),
        origin,
        path,
        PartnerTrustConfiguration.Authentication.none(),
        PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
        Optional.empty(),
        new PartnerTrustConfiguration.Limits(1_000, 2_000, 5_000, 100_000, 200_000, 5, 100, 20, 3),
        PartnerTrustConfiguration.RetryClass.IDEMPOTENT_READ,
        List.of(OPERATION));
  }
}
