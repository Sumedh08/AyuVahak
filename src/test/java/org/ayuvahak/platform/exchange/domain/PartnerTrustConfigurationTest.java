package org.ayuvahak.platform.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Limits;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.RetryClass;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy;
import org.junit.jupiter.api.Test;

class PartnerTrustConfigurationTest {

  @Test
  void canonicalizesPartnerOriginAndRejectsUnsafeEndpointForms() {
    assertThat(
            PartnerTrustConfiguration.normalizeHttpsOrigin(
                "HTTPS://EXAMPLE.test:443", "partner endpoint origin"))
        .isEqualTo("https://example.test");

    assertThatThrownBy(
            () ->
                PartnerTrustConfiguration.normalizeHttpsOrigin(
                    "http://example.test", "partner endpoint origin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PartnerTrustConfiguration.normalizeHttpsOrigin(
                    "https://user:secret@example.test", "partner endpoint origin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PartnerTrustConfiguration.normalizeHttpsOrigin(
                    "https://example.test/unapproved", "partner endpoint origin"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void operationCodesAreOpaqueToThePlatformTrustBoundary() {
    AllowedOperation futureOwnerOperation =
        new AllowedOperation(
            OwnerContext.DIRECTORY,
            "directory.future.read",
            Direction.OUTBOUND,
            Protocol.FHIR_R4_JSON,
            "DirectoryProfile-1");

    assertThat(futureOwnerOperation.operationCode()).isEqualTo("directory.future.read");
  }

  @Test
  void oauthAndTlsReferencesAreValidatedWithoutAcceptingCredentialMaterial() {
    assertThatThrownBy(
            () ->
                new Authentication(
                    AuthenticationMethod.OAUTH_PRIVATE_KEY_JWT,
                    Optional.empty(),
                    Optional.of("https://auth.example.test"),
                    Optional.of("/oauth/token"),
                    Optional.of("https://auth.example.test/issuer"),
                    Optional.of("ayuvahak"),
                    Optional.of("synthetic-client")))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new Authentication(
                    AuthenticationMethod.MUTUAL_TLS,
                    Optional.of("-----BEGIN PRIVATE KEY-----"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void oauthProfileRequiresASeparateReferenceForTrustedTokenSigningKeys() {
    assertThatThrownBy(
            () ->
                new Authentication(
                    AuthenticationMethod.OAUTH_PRIVATE_KEY_JWT,
                    Optional.of("env:SYN_CLIENT_KEY"),
                    Optional.of("https://auth.example.test"),
                    Optional.of("/oauth/token"),
                    Optional.of("https://auth.example.test/issuer"),
                    Optional.of("ayuvahak"),
                    Optional.of("synthetic-client")))
        .isInstanceOf(IllegalArgumentException.class);

    Authentication configured =
        new Authentication(
            AuthenticationMethod.OAUTH_PRIVATE_KEY_JWT,
            Optional.of("env:SYN_CLIENT_KEY"),
            Optional.of("env:SYN_TOKEN_JWKS"),
            Optional.of("https://auth.example.test"),
            Optional.of("/oauth/token"),
            Optional.of("https://auth.example.test/issuer"),
            Optional.of("ayuvahak"),
            Optional.of("synthetic-client"));
    assertThat(configured.tokenSigningKeysReference()).contains("env:SYN_TOKEN_JWKS");
    assertThat(configured.toString()).doesNotContain("SYN_CLIENT_KEY", "SYN_TOKEN_JWKS");
  }

  @Test
  void inboundProfilesCannotDeclareAutomaticRetry() {
    assertThatThrownBy(
            () ->
                profile(
                    new AllowedOperation(
                        OwnerContext.DIRECTORY,
                        "mcsd.iti130.feed",
                        Direction.INBOUND,
                        Protocol.FHIR_R4_JSON,
                        "IHE-mCSD"),
                    RetryClass.IDEMPOTENT_READ,
                    3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inboundProfilesRequireOneExplicitServicePrincipal() {
    AllowedOperation inbound =
        new AllowedOperation(
            OwnerContext.DIRECTORY,
            "mcsd.iti130.feed",
            Direction.INBOUND,
            Protocol.FHIR_R4_JSON,
            "IHE-mCSD");

    assertThatThrownBy(() -> profile(inbound, RetryClass.NEVER, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("service principal");

    PartnerTrustConfiguration configured =
        new PartnerTrustConfiguration(
            new PartnerKey("synthetic-directory"),
            new ExternalReference(
                new IssuerId("urn:synthetic:directory"), "Organization", "SYN-ORG-001"),
            "https://directory.example.test",
            "/fhir",
            Authentication.none(),
            TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new Limits(1_000, 2_000, 5_000, 1_000_000, 2_000_000, 10, 500, 32, 1),
            RetryClass.NEVER,
            List.of(inbound),
            Optional.of(
                ActorReference.service(
                    new IssuerId("https://identity.example.test/realm"), "synthetic-partner")));

    assertThat(configured.inboundPrincipal())
        .contains(
            ActorReference.service(
                new IssuerId("https://identity.example.test/realm"), "synthetic-partner"));
  }

  private static PartnerTrustConfiguration profile(
      AllowedOperation operation, RetryClass retryClass, int maxAttempts) {
    return new PartnerTrustConfiguration(
        new PartnerKey("synthetic-directory"),
        new ExternalReference(
            new IssuerId("urn:synthetic:directory"), "Organization", "SYN-ORG-001"),
        "https://directory.example.test",
        "/fhir",
        Authentication.none(),
        TlsPolicy.SYSTEM_CA,
        Optional.empty(),
        new Limits(1_000, 2_000, 5_000, 1_000_000, 2_000_000, 10, 500, 32, maxAttempts),
        retryClass,
        List.of(operation));
  }
}
