package org.ayuvahak.platform.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.junit.jupiter.api.Test;

class PartnerOutboundOperationServiceTest {

  private static final PartnerKey PARTNER = new PartnerKey("SYNTHETIC-DHIS2-PARTNER");
  private static final PartnerTrustConfiguration.AllowedOperation OPERATION =
      PartnerTrustConfiguration.AllowedOperation.analyticsDhis2DataValueSet();

  @Test
  void staleExpectedConfigurationVersionIsDeniedBeforeCredentialsOrNetwork() {
    PartnerProfileVersion active = profile(2);
    AtomicInteger credentialCalls = new AtomicInteger();
    AtomicInteger transportCalls = new AtomicInteger();
    AtomicInteger invalidations = new AtomicInteger();
    PartnerAuthorizationPort authorization =
        new PartnerAuthorizationPort() {
          @Override
          public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion profile) {
            credentialCalls.incrementAndGet();
            return Optional.empty();
          }

          @Override
          public void invalidate(PartnerKey partnerKey) {
            assertThat(partnerKey).isEqualTo(PARTNER);
            invalidations.incrementAndGet();
          }
        };
    PartnerOutboundOperationService service =
        new PartnerOutboundOperationService(
            activeProfileStore(active),
            authorization,
            (profile, request, token, beforeAttempt) -> {
              transportCalls.incrementAndGet();
              return new PartnerOutboundOperationResponse(
                  200, Optional.empty(), new byte[0], profile.version());
            });
    PartnerOutboundOperationRequest request =
        new PartnerOutboundOperationRequest(
            PARTNER,
            OPERATION,
            List.of("dataValueSets"),
            "synthetic-body".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "export-SYN-OPERATION-001",
            Optional.empty(),
            Optional.of(1));

    assertThatThrownBy(() -> service.execute(request))
        .isInstanceOf(PartnerTransportException.class)
        .extracting(failure -> ((PartnerTransportException) failure).failure())
        .isEqualTo(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    assertThat(credentialCalls).hasValue(0);
    assertThat(transportCalls).hasValue(0);
    assertThat(invalidations).hasValue(1);
  }

  private static PartnerTrustProfileStore activeProfileStore(PartnerProfileVersion profile) {
    return (PartnerTrustProfileStore)
        java.lang.reflect.Proxy.newProxyInstance(
            PartnerTrustProfileStore.class.getClassLoader(),
            new Class<?>[] {PartnerTrustProfileStore.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("findActive")) {
                return Optional.of(profile);
              }
              throw new UnsupportedOperationException("unexpected trust-store operation");
            });
  }

  private static PartnerProfileVersion profile(int version) {
    Instant now = Instant.parse("2026-10-18T00:00:00Z");
    PartnerTrustConfiguration configuration =
        new PartnerTrustConfiguration(
            PARTNER,
            new ExternalReference(
                new IssuerId("urn:synthetic:analytics"), "Organization", "SYN-ORG-001"),
            "https://partner.example.test",
            "/api",
            new PartnerTrustConfiguration.Authentication(
                PartnerTrustConfiguration.AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS,
                Optional.of("env:SYNTHETIC-CLIENT-SECRET"),
                Optional.of("env:SYNTHETIC-OAUTH-KEYS"),
                Optional.of("https://identity.example.test"),
                Optional.of("/oauth/token"),
                Optional.of("https://identity.example.test/issuer"),
                Optional.of("SYNTHETIC-AUDIENCE"),
                Optional.of("SYNTHETIC-CLIENT")),
            PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new PartnerTrustConfiguration.Limits(1_000, 2_000, 5_000, 10_000, 10_000, 1, 10, 5, 1),
            PartnerTrustConfiguration.RetryClass.NEVER,
            List.of(OPERATION));
    return new PartnerProfileVersion(
        InternalId.generate(), version, PartnerProfileState.ACTIVE, configuration, now, now);
  }
}
