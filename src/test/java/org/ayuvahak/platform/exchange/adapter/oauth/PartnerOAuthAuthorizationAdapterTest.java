package org.ayuvahak.platform.exchange.adapter.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.PartnerBearerToken;
import org.ayuvahak.platform.exchange.application.PartnerSecretMaterial;
import org.ayuvahak.platform.exchange.application.PartnerSecretProvider;
import org.ayuvahak.platform.exchange.application.PartnerSecretProviderUnavailableException;
import org.ayuvahak.platform.exchange.application.PartnerTokenEndpointPort;
import org.ayuvahak.platform.exchange.application.PartnerTokenEndpointRequest;
import org.ayuvahak.platform.exchange.application.PartnerTokenEndpointResponse;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class PartnerOAuthAuthorizationAdapterTest {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  private static final String CLIENT_ID = "synthetic-client";
  private static final String ISSUER = "https://auth.example.test/issuer";
  private static final String AUDIENCE = "ayuvahak-resource";
  private static final String TOKEN_ENDPOINT = "https://auth.example.test/oauth/token";

  @Test
  void privateKeyJwtUsesConfiguredTokenEndpointAndCachesValidatedPartnerToken() throws Exception {
    Fixture fixture = new Fixture(TokenMutation.VALID);

    PartnerBearerToken first = fixture.adapter.bearerToken(fixture.profile).orElseThrow();
    PartnerBearerToken second = fixture.adapter.bearerToken(fixture.profile).orElseThrow();

    assertThat(second).isSameAs(first);
    assertThat(fixture.tokenCalls).isEqualTo(1);
    assertThat(first.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    assertThat(first.toString()).doesNotContain("synthetic-access-token");
    assertThat(fixture.lastRequest.toString())
        .doesNotContain(fixture.clientPrivateKey.toJSONString());
    fixture.adapter.invalidate(fixture.profile.configuration().partnerKey());
    fixture.adapter.bearerToken(fixture.profile).orElseThrow();
    assertThat(fixture.tokenCalls).isEqualTo(2);
  }

  @Test
  void clientSecretProfileUsesBasicAuthenticationAndKeepsSecretOutOfDiagnostics() throws Exception {
    Fixture fixture =
        new Fixture(TokenMutation.VALID, AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS);

    PartnerBearerToken token = fixture.adapter.bearerToken(fixture.profile).orElseThrow();

    String credentials =
        Base64.getEncoder()
            .encodeToString(
                "synthetic-client:synthetic-client-secret".getBytes(StandardCharsets.UTF_8));
    assertThat(fixture.lastRequest.basicAuthorization()).isEqualTo("Basic " + credentials);
    assertThat(parseForm(fixture.lastRequest.formBody()))
        .containsExactly(Map.entry("grant_type", "client_credentials"));
    assertThat(fixture.lastRequest.toString()).doesNotContain("synthetic-client-secret");
    assertThat(token.toString()).doesNotContain("synthetic-access-token");
  }

  @Test
  void rotatedSecretIsUsedAfterTheCachedBearerTokenExpires() throws Exception {
    Fixture fixture =
        new Fixture(TokenMutation.VALID, AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS);
    PartnerBearerToken initial = fixture.adapter.bearerToken(fixture.profile).orElseThrow();

    fixture.secrets.put("env:SYNTH_CLIENT_SECRET", "synthetic-rotated-client-secret");
    fixture.clock.advance(Duration.ofSeconds(120));
    assertThat(fixture.adapter.bearerToken(fixture.profile)).containsSame(initial);
    assertThat(fixture.tokenCalls).isEqualTo(1);

    fixture.clock.advance(Duration.ofSeconds(271));
    fixture.adapter.bearerToken(fixture.profile).orElseThrow();

    String expectedCredentials =
        Base64.getEncoder()
            .encodeToString(
                "synthetic-client:synthetic-rotated-client-secret"
                    .getBytes(StandardCharsets.UTF_8));
    assertThat(fixture.tokenCalls).isEqualTo(2);
    assertThat(fixture.lastRequest.basicAuthorization()).isEqualTo("Basic " + expectedCredentials);
  }

  @Test
  void revokedSecretCannotFallBackToAnOlderCachedBearerToken() throws Exception {
    Fixture fixture =
        new Fixture(TokenMutation.VALID, AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS);
    fixture.adapter.bearerToken(fixture.profile).orElseThrow();
    fixture.secrets.remove("env:SYNTH_CLIENT_SECRET");
    fixture.clock.advance(Duration.ofSeconds(271));

    assertThatThrownBy(() -> fixture.adapter.bearerToken(fixture.profile))
        .isInstanceOf(PartnerTransportException.class)
        .extracting(failure -> ((PartnerTransportException) failure).failure())
        .isEqualTo(PartnerTransportFailure.AUTHENTICATION_FAILURE);
    assertThat(fixture.tokenCalls).isEqualTo(1);
  }

  @Test
  void providerOutageAfterTokenExpiryFailsSafelyWithoutReusingAnOlderToken() throws Exception {
    Fixture fixture =
        new Fixture(TokenMutation.VALID, AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS);
    fixture.adapter.bearerToken(fixture.profile).orElseThrow();
    fixture.providerUnavailable = true;
    fixture.clock.advance(Duration.ofSeconds(271));

    assertThatThrownBy(() -> fixture.adapter.bearerToken(fixture.profile))
        .isInstanceOf(PartnerTransportException.class)
        .extracting(failure -> ((PartnerTransportException) failure).failure())
        .isEqualTo(PartnerTransportFailure.AUTHENTICATION_FAILURE);
    assertThat(fixture.tokenCalls).isEqualTo(1);
  }

  @Test
  void rejectsPrivateMaterialInConfiguredTokenVerificationKeys() throws Exception {
    Fixture fixture = new Fixture(TokenMutation.VALID, AuthenticationMethod.OAUTH_PRIVATE_KEY_JWT);
    fixture.keySetOverride =
        new JWKSet(
                new OctetSequenceKey.Builder(new byte[32])
                    .keyID("synthetic-private-token-key")
                    .build())
            .toString();

    assertThatThrownBy(() -> fixture.adapter.bearerToken(fixture.profile))
        .isInstanceOf(PartnerTransportException.class)
        .extracting(failure -> ((PartnerTransportException) failure).failure())
        .isEqualTo(PartnerTransportFailure.AUTHENTICATION_FAILURE);
  }

  @ParameterizedTest
  @EnumSource(
      value = TokenMutation.class,
      names = {
        "WRONG_ISSUER",
        "WRONG_AUDIENCE",
        "WRONG_CLIENT",
        "EXPIRED",
        "NOT_YET_VALID",
        "BAD_SIGNATURE",
        "WRONG_ALGORITHM"
      })
  void rejectsInvalidPartnerAccessTokens(TokenMutation mutation) throws Exception {
    Fixture fixture = new Fixture(mutation);

    assertThatThrownBy(() -> fixture.adapter.bearerToken(fixture.profile))
        .isInstanceOf(PartnerTransportException.class)
        .extracting(failure -> ((PartnerTransportException) failure).failure())
        .isEqualTo(PartnerTransportFailure.AUTHENTICATION_FAILURE);
  }

  @Test
  void evictsCachedTokenWhenProfileVersionChanges() throws Exception {
    Fixture fixture = new Fixture(TokenMutation.VALID);
    fixture.adapter.bearerToken(fixture.profile).orElseThrow();

    PartnerProfileVersion nextVersion = fixture.profile(2);
    fixture.adapter.bearerToken(nextVersion).orElseThrow();

    assertThat(fixture.tokenCalls).isEqualTo(2);
  }

  private enum TokenMutation {
    VALID,
    WRONG_ISSUER,
    WRONG_AUDIENCE,
    WRONG_CLIENT,
    EXPIRED,
    NOT_YET_VALID,
    BAD_SIGNATURE,
    WRONG_ALGORITHM
  }

  private static final class Fixture {

    private final RSAKey clientPrivateKey = newSigningKey("synthetic-client-key");
    private final RSAKey accessSigningKey = newSigningKey("synthetic-access-key");
    private final RSAKey otherSigningKey = newSigningKey("synthetic-other-key");
    private final TokenMutation mutation;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthenticationMethod authenticationMethod;
    private final MutableClock clock = new MutableClock(NOW);
    private final Map<String, String> secrets = new HashMap<>();
    private final PartnerProfileVersion profile;
    private final PartnerOAuthAuthorizationAdapter adapter;
    private String keySetOverride;
    private boolean providerUnavailable;
    private int tokenCalls;
    private PartnerTokenEndpointRequest lastRequest;

    private Fixture(TokenMutation mutation) throws Exception {
      this(mutation, AuthenticationMethod.OAUTH_PRIVATE_KEY_JWT);
    }

    private Fixture(TokenMutation mutation, AuthenticationMethod authenticationMethod)
        throws Exception {
      this.mutation = mutation;
      this.authenticationMethod = authenticationMethod;
      this.profile = profile(1);
      secrets.put("env:SYNTH_CLIENT_PRIVATE_JWK", clientPrivateKey.toJSONString());
      secrets.put("env:SYNTH_CLIENT_SECRET", "synthetic-client-secret");
      secrets.put(
          "env:SYNTH_ACCESS_TOKEN_JWKS", new JWKSet(accessSigningKey.toPublicJWK()).toString());
      PartnerSecretProvider provider =
          reference -> {
            if (providerUnavailable) {
              throw new PartnerSecretProviderUnavailableException();
            }
            return Optional.ofNullable(
                    "env:SYNTH_ACCESS_TOKEN_JWKS".equals(reference) && keySetOverride != null
                        ? keySetOverride
                        : secrets.get(reference))
                .map(value -> new PartnerSecretMaterial(value.toCharArray()));
          };
      PartnerTokenEndpointPort endpoint =
          (activeProfile, request) -> {
            try {
              tokenCalls++;
              lastRequest = request;
              Map<String, String> form = parseForm(request.formBody());
              assertThat(form.get("grant_type")).isEqualTo("client_credentials");
              if (authenticationMethod == AuthenticationMethod.OAUTH_PRIVATE_KEY_JWT) {
                assertThat(request.basicAuthorization()).isNull();
                assertThat(form.get("client_id")).isEqualTo(CLIENT_ID);
                assertThat(form.get("client_assertion_type"))
                    .isEqualTo("urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
                SignedJWT assertion = SignedJWT.parse(form.get("client_assertion"));
                assertThat(assertion.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
                assertThat(assertion.getJWTClaimsSet().getIssuer()).isEqualTo(CLIENT_ID);
                assertThat(assertion.getJWTClaimsSet().getSubject()).isEqualTo(CLIENT_ID);
                assertThat(assertion.getJWTClaimsSet().getAudience())
                    .containsExactly(TOKEN_ENDPOINT);
                assertThat(assertion.getJWTClaimsSet().getJWTID()).isNotBlank();
                assertThat(assertion.verify(new RSASSAVerifier(clientPrivateKey.toRSAPublicKey())))
                    .isTrue();
              } else {
                String expectedCredentials =
                    Base64.getEncoder()
                        .encodeToString(
                            (CLIENT_ID + ":" + secrets.get("env:SYNTH_CLIENT_SECRET"))
                                .getBytes(StandardCharsets.UTF_8));
                assertThat(request.basicAuthorization()).isEqualTo("Basic " + expectedCredentials);
              }
              String accessToken = accessToken(mutation);
              byte[] body =
                  mapper.writeValueAsBytes(
                      Map.of(
                          "access_token", accessToken, "token_type", "Bearer", "expires_in", 300));
              return new PartnerTokenEndpointResponse(
                  200, Optional.of("application/json"), body, activeProfile.version());
            } catch (Exception exception) {
              throw new AssertionError("synthetic token endpoint validation failed", exception);
            }
          };
      adapter = new PartnerOAuthAuthorizationAdapter(provider, endpoint, mapper, clock);
    }

    private PartnerProfileVersion profile(int version) {
      PartnerTrustConfiguration configuration =
          new PartnerTrustConfiguration(
              new PartnerKey("SYNTHETIC-PARTNER"),
              new ExternalReference(
                  new IssuerId("urn:synthetic:partners"), "Organization", "SYN-ORG-001"),
              "https://partner.example.test",
              "/fhir",
              new Authentication(
                  authenticationMethod,
                  Optional.of(
                      authenticationMethod == AuthenticationMethod.OAUTH_PRIVATE_KEY_JWT
                          ? "env:SYNTH_CLIENT_PRIVATE_JWK"
                          : "env:SYNTH_CLIENT_SECRET"),
                  Optional.of("env:SYNTH_ACCESS_TOKEN_JWKS"),
                  Optional.of("https://auth.example.test"),
                  Optional.of("/oauth/token"),
                  Optional.of(ISSUER),
                  Optional.of(AUDIENCE),
                  Optional.of(CLIENT_ID)),
              TlsPolicy.SYSTEM_CA,
              Optional.empty(),
              new Limits(1_000, 2_000, 5_000, 1_000_000, 2_000_000, 10, 500, 32, 3),
              RetryClass.IDEMPOTENT_READ,
              List.of(
                  new AllowedOperation(
                      OwnerContext.DIRECTORY,
                      "mcsd.iti90.search",
                      Direction.OUTBOUND,
                      Protocol.FHIR_R4_JSON,
                      "IHE-mCSD")));
      return new PartnerProfileVersion(
          InternalId.generate(), version, PartnerProfileState.ACTIVE, configuration, NOW, NOW);
    }

    private String accessToken(TokenMutation mutation) throws Exception {
      Instant issue = clock.instant().minusSeconds(1);
      Instant expiry = clock.instant().plusSeconds(300);
      String issuer = ISSUER;
      String audience = AUDIENCE;
      String subject = CLIENT_ID;
      Instant notBefore = issue;
      RSAKey signer = accessSigningKey;
      JWSAlgorithm algorithm = JWSAlgorithm.RS256;
      if (mutation == TokenMutation.WRONG_ISSUER) {
        issuer = "https://untrusted.example.test/issuer";
      } else if (mutation == TokenMutation.WRONG_AUDIENCE) {
        audience = "another-service";
      } else if (mutation == TokenMutation.WRONG_CLIENT) {
        subject = "another-client";
      } else if (mutation == TokenMutation.EXPIRED) {
        expiry = NOW.minusSeconds(1);
      } else if (mutation == TokenMutation.NOT_YET_VALID) {
        notBefore = NOW.plusSeconds(120);
      } else if (mutation == TokenMutation.BAD_SIGNATURE) {
        signer = otherSigningKey;
      } else if (mutation == TokenMutation.WRONG_ALGORITHM) {
        algorithm = JWSAlgorithm.HS256;
      }
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject(subject)
              .audience(audience)
              .issueTime(Date.from(issue))
              .notBeforeTime(Date.from(notBefore))
              .expirationTime(Date.from(expiry))
              .build();
      if (algorithm.equals(JWSAlgorithm.HS256)) {
        SignedJWT token =
            new SignedJWT(
                new JWSHeader.Builder(algorithm).keyID("synthetic-access-key").build(), claims);
        token.sign(
            new MACSigner(
                "synthetic-test-only-hmac-secret-key-000000".getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
      }
      SignedJWT token =
          new SignedJWT(
              new JWSHeader.Builder(algorithm)
                  .type(JOSEObjectType.JWT)
                  .keyID(signer.getKeyID())
                  .build(),
              claims);
      token.sign(new RSASSASigner(signer.toPrivateKey()));
      return token.serialize();
    }
  }

  private static Map<String, String> parseForm(String form) {
    return java.util.Arrays.stream(form.split("&"))
        .map(part -> part.split("=", 2))
        .collect(
            java.util.stream.Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
  }

  private static RSAKey newSigningKey(String keyId) {
    try {
      return new RSAKeyGenerator(2_048).keyID(keyId).generate();
    } catch (Exception exception) {
      throw new AssertionError("synthetic token key generation failed", exception);
    }
  }

  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
