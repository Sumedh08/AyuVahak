package org.ayuvahak.platform.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformExceptionHandler;
import org.ayuvahak.platform.api.SystemInformationController;
import org.ayuvahak.testsupport.authentication.AuthenticationTestConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
    controllers = {
      SystemInformationController.class,
      AuthenticationHttpIntegrationTest.ActorProbe.class
    },
    properties = {
      "ayuvahak.platform.service-name=AyuVahak",
      "ayuvahak.database.url=jdbc:postgresql://localhost:5432/unused",
      "ayuvahak.database.username=test",
      "ayuvahak.database.password=local-test-only",
      "ayuvahak.authentication.issuer=https://identity.example.test/realms/ayuvahak",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=transfer-agent",
      "ayuvahak.authentication.allow-http=true"
    })
@Import({
  AyuVahakSecurityConfiguration.class,
  CorrelationIdFilter.class,
  PlatformExceptionHandler.class,
  AuthenticationHttpIntegrationTest.ActorProbe.class,
  AuthenticationTestConfiguration.class
})
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationHttpIntegrationTest {

  private static final String ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String AUDIENCE = "ayuvahak-api";
  private static final RSAKey SIGNING_KEY = newSigningKey("fixture-signing-key");
  private static final AtomicBoolean JWK_SET_AVAILABLE = new AtomicBoolean(true);
  private static HttpServer jwksServer;

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthenticationTestConfiguration.TestAuditRecordAppender auditAppender;

  @org.junit.jupiter.api.BeforeEach
  void resetAuditFixture() {
    auditAppender.reset();
  }

  @AfterEach
  void restoreLocalJwksAvailability() {
    JWK_SET_AVAILABLE.set(true);
  }

  @DynamicPropertySource
  static void jwksConfiguration(DynamicPropertyRegistry registry) {
    registry.add(
        "ayuvahak.authentication.jwk-set-uri",
        () -> "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
  }

  @BeforeAll
  static void startLocalJwksServer() throws Exception {
    jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwksServer.createContext(
        "/jwks",
        exchange -> {
          if (!JWK_SET_AVAILABLE.get()) {
            byte[] response =
                "temporarily unavailable".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, response.length);
            try (var responseBody = exchange.getResponseBody()) {
              responseBody.write(response);
            }
            return;
          }
          byte[] response =
              new JWKSet(SIGNING_KEY.toPublicJWK())
                  .toString()
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(response);
          }
        });
    jwksServer.start();
  }

  @AfterAll
  static void stopLocalJwksServer() {
    if (jwksServer != null) {
      jwksServer.stop(0);
    }
  }

  @Test
  void leavesSystemInformationPublicAndRejectsProtectedRequestWithoutBearerToken()
      throws Exception {
    mockMvc.perform(get("/api/v1/system-info")).andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/platform/__test/actor").header("X-Correlation-ID", "auth-corr-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.correlationId").value("auth-corr-1"))
        .andExpect(header().string("X-Correlation-ID", "auth-corr-1"));
    assertThat(auditAppender.lastEvidence())
        .satisfies(
            evidence -> {
              assertThat(evidence.actionCode()).isEqualTo("AUTHENTICATION_FAILURE");
              assertThat(evidence.authenticationEvidence())
                  .isEqualTo(org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence.NONE);
              assertThat(evidence.correlationId().value()).isEqualTo("auth-corr-1");
            });
  }

  @Test
  void returnsAuditUnavailableWhenAuthenticationFailureCannotBeRecorded() throws Exception {
    auditAppender.failWrites();

    mockMvc
        .perform(get("/api/v1/platform/__test/actor"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
  }

  @Test
  void authenticatesDistinctHumanAndServicePrincipalsWithoutMappingProviderRoles()
      throws Exception {
    mockMvc
        .perform(
            get("/api/v1/platform/__test/actor").header("Authorization", bearer(token(Map.of()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("HUMAN"));

    mockMvc
        .perform(
            get("/api/v1/platform/__test/actor")
                .header(
                    "Authorization",
                    bearer(
                        token(Map.of("client_id", "transfer-agent", "roles", List.of("admin"))))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("SERVICE"));
  }

  @Test
  void rejectsInvalidIssuerAudienceLifetimeTypeAndPrincipalClaims() throws Exception {
    assertUnauthorized(
        token(
            Map.of(),
            ISSUER,
            List.of("other-api"),
            Instant.now().plusSeconds(300),
            Instant.now().minusSeconds(1),
            "Bearer",
            SIGNING_KEY));
    assertUnauthorized(
        token(
            Map.of(),
            "https://wrong.example.test",
            List.of(AUDIENCE),
            Instant.now().plusSeconds(300),
            Instant.now().minusSeconds(1),
            "Bearer",
            SIGNING_KEY));
    assertUnauthorized(
        token(
            Map.of(),
            ISSUER,
            List.of(AUDIENCE),
            Instant.now().minusSeconds(30),
            Instant.now().minusSeconds(60),
            "Bearer",
            SIGNING_KEY));
    assertUnauthorized(
        token(
            Map.of(),
            ISSUER,
            List.of(AUDIENCE),
            Instant.now().plusSeconds(300),
            Instant.now().plusSeconds(300),
            "Bearer",
            SIGNING_KEY));
    assertUnauthorized(
        token(
            Map.of(),
            ISSUER,
            List.of(AUDIENCE),
            Instant.now().plusSeconds(300),
            Instant.now().minusSeconds(1),
            "ID",
            SIGNING_KEY));
    assertUnauthorized(token(Map.of("client_id", "not-allowlisted")));
    assertUnauthorized(token(Map.of("sub", "")));
  }

  @Test
  void rejectsBadSignaturesAndUnknownKeysWhenJwksIsReachable() throws Exception {
    RSAKey attacker = newSigningKey("fixture-attacker-key");
    assertUnauthorized(
        token(
            Map.of(),
            ISSUER,
            List.of(AUDIENCE),
            Instant.now().plusSeconds(300),
            Instant.now().minusSeconds(1),
            "Bearer",
            attacker));

    RSAKey unknownKid = newSigningKey("unpublished-key");
    assertUnauthorized(
        token(
            Map.of(),
            ISSUER,
            List.of(AUDIENCE),
            Instant.now().plusSeconds(300),
            Instant.now().minusSeconds(1),
            "Bearer",
            unknownKid));

    RSAKey missingKid = newSigningKey(null);
    assertUnauthorized(
        token(
            Map.of(),
            ISSUER,
            List.of(AUDIENCE),
            Instant.now().plusSeconds(300),
            Instant.now().minusSeconds(1),
            "Bearer",
            missingKid));
    assertUnauthorized(
        token(
            Map.of(),
            ISSUER,
            List.of(AUDIENCE),
            Instant.now().plusSeconds(300),
            Instant.now().minusSeconds(1),
            "Bearer",
            JWSAlgorithm.RS512,
            newSigningKey("unsupported-algorithm-key")));
  }

  @Test
  void usesCachedTrustedKeyDuringOutageAndFailsClosedWithTemporaryProblemForUnknownKey()
      throws Exception {
    mockMvc
        .perform(
            get("/api/v1/platform/__test/actor").header("Authorization", bearer(token(Map.of()))))
        .andExpect(status().isOk());

    JWK_SET_AVAILABLE.set(false);
    mockMvc
        .perform(
            get("/api/v1/platform/__test/actor").header("Authorization", bearer(token(Map.of()))))
        .andExpect(status().isOk());

    RSAKey unknownKid = newSigningKey("unavailable-source-key");
    mockMvc
        .perform(
            get("/api/v1/platform/__test/actor")
                .header(
                    "Authorization",
                    bearer(
                        token(
                            Map.of(),
                            ISSUER,
                            List.of(AUDIENCE),
                            Instant.now().plusSeconds(300),
                            Instant.now().minusSeconds(1),
                            "Bearer",
                            unknownKid))))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"));
  }

  @Test
  void doesNotWriteBearerTokenOrClaimsToCapturedOutput(CapturedOutput output) throws Exception {
    String bearerToken = token(Map.of("synthetic_sensitive_claim", "fictional-value"));
    mockMvc
        .perform(get("/api/v1/platform/__test/actor").header("Authorization", bearer(bearerToken)))
        .andExpect(status().isOk());

    assertThat(output.getAll())
        .doesNotContain(bearerToken)
        .doesNotContain("synthetic_sensitive_claim")
        .doesNotContain("fictional-value");
  }

  private void assertUnauthorized(String jwt) throws Exception {
    mockMvc
        .perform(get("/api/v1/platform/__test/actor").header("Authorization", bearer(jwt)))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  private static String token(Map<String, Object> extraClaims) throws Exception {
    return token(
        extraClaims,
        ISSUER,
        List.of(AUDIENCE),
        Instant.now().plusSeconds(300),
        Instant.now().minusSeconds(1),
        "Bearer",
        SIGNING_KEY);
  }

  private static String token(
      Map<String, Object> extraClaims,
      String issuer,
      List<String> audiences,
      Instant expiresAt,
      Instant notBefore,
      String tokenType,
      RSAKey signingKey)
      throws Exception {
    return token(
        extraClaims,
        issuer,
        audiences,
        expiresAt,
        notBefore,
        tokenType,
        JWSAlgorithm.RS256,
        signingKey);
  }

  private static String token(
      Map<String, Object> extraClaims,
      String issuer,
      List<String> audiences,
      Instant expiresAt,
      Instant notBefore,
      String tokenType,
      JWSAlgorithm algorithm,
      RSAKey signingKey)
      throws Exception {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("synthetic-person-17")
            .audience(audiences)
            .issueTime(Date.from(Instant.now().minusSeconds(5)))
            .notBeforeTime(Date.from(notBefore))
            .expirationTime(Date.from(expiresAt))
            .claim("typ", tokenType);
    extraClaims.forEach(claims::claim);
    JWSHeader.Builder header = new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT);
    if (signingKey.getKeyID() != null) {
      header.keyID(signingKey.getKeyID());
    }
    SignedJWT jwt = new SignedJWT(header.build(), claims.build());
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private static RSAKey newSigningKey(String keyId) {
    try {
      RSAKeyGenerator generator = new RSAKeyGenerator(2048);
      return keyId == null ? generator.generate() : generator.keyID(keyId).generate();
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Could not create a deterministic local test key.", exception);
    }
  }

  @RestController
  static class ActorProbe {
    @GetMapping("/api/v1/platform/__test/actor")
    Map<String, String> actorKind() {
      var actor =
          (org.ayuvahak.platform.core.ActorReference)
              org.springframework.security.core.context.SecurityContextHolder.getContext()
                  .getAuthentication()
                  .getPrincipal();
      return Map.of("kind", actor.kind().name());
    }
  }
}
