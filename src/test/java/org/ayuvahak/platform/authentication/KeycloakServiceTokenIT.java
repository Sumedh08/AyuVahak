package org.ayuvahak.platform.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Verifies actual client-credentials tokens from the local reference provider. */
@Testcontainers
class KeycloakServiceTokenIT {

  private static final String LOCAL_ADMIN_PASSWORD = "local-only-not-a-secret";
  private static final String SERVICE_CLIENT_ID = "transfer-agent";
  private static final String SERVICE_CLIENT_SECRET = "SYNTHETIC-KEYCLOAK-TEST-CLIENT-SECRET";
  private static final String DEMO_PASSWORD = "SYNTHETIC-KEYCLOAK-TEST-DEMO-PASSWORD";
  private static final Path TEST_REALM_EXPORT = createTestRealmExport();

  @Container
  private static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>(
              DockerImageName.parse(
                  "quay.io/keycloak/keycloak:26.8.0@sha256:b0f60d489d51c5d113390bdf5461d4c06e6051be026c05549f2e1e10ec352bcc"))
          .withExposedPorts(8080)
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "local-admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", LOCAL_ADMIN_PASSWORD)
          .withEnv("KC_HOSTNAME_STRICT", "false")
          .withCopyFileToContainer(
              MountableFile.forHostPath(TEST_REALM_EXPORT.toString()),
              "/opt/keycloak/data/import/ayuvahak-realm.json")
          .withCommand("start-dev", "--import-realm", "--hostname-strict=false")
          .waitingFor(
              Wait.forHttp("/realms/ayuvahak/.well-known/openid-configuration")
                  .forPort(8080)
                  .forStatusCode(200)
                  // The clean full-suite run completed realm import at the former five-minute
                  // bound.
                  .withStartupTimeout(Duration.ofMinutes(10)));

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  @Test
  void verifiesServiceTokenFromLocalKeycloakClientCredentialsFlow() throws Exception {
    String origin = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    HttpResponse<String> discovery =
        httpClient.send(
            HttpRequest.newBuilder(
                    URI.create(origin + "/realms/ayuvahak/.well-known/openid-configuration"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(discovery.statusCode()).isEqualTo(200);
    String issuer = JsonPath.read(discovery.body(), "$.issuer");
    String jwkSetUri = JsonPath.read(discovery.body(), "$.jwks_uri");

    HttpResponse<String> tokenResponse =
        httpClient.send(
            HttpRequest.newBuilder(
                    URI.create(origin + "/realms/ayuvahak/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(60))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials"
                            + "&client_id="
                            + encode(SERVICE_CLIENT_ID)
                            + "&client_secret="
                            + encode(SERVICE_CLIENT_SECRET)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(tokenResponse.statusCode()).isEqualTo(200);
    String accessToken = JsonPath.read(tokenResponse.body(), "$.access_token");
    assertThat(SignedJWT.parse(accessToken).getJWTClaimsSet().getAudience())
        .contains("ayuvahak-api");

    AuthenticationProperties properties =
        new AuthenticationProperties(
            issuer, jwkSetUri, "ayuvahak-api", Set.of(SERVICE_CLIENT_ID), true);
    var jwt =
        new AyuVahakSecurityConfiguration().ayuVahakJwtDecoder(properties).decode(accessToken);
    var actor =
        new TokenPrincipalResolver(new IssuerId(issuer), properties.serviceClientIds())
            .resolve(jwt.getClaims());

    assertThat(actor.kind()).isEqualTo(ActorKind.SERVICE);
    assertThat(actor.identity().issuer().value()).isEqualTo(issuer);
    assertThat(actor.identity().resourceType()).isEqualTo("client_id");
    assertThat(actor.identity().value()).isEqualTo(SERVICE_CLIENT_ID);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static Path createTestRealmExport() {
    try {
      String realm =
          Files.readString(Path.of("infra", "keycloak", "realm-export.json").toAbsolutePath())
              .replace("__AYUVAHAK_TRANSFER_AGENT_CLIENT_SECRET__", SERVICE_CLIENT_SECRET)
              .replace("__AYUVAHAK_DEMO_PASSWORD__", DEMO_PASSWORD);
      if (realm.contains("__AYUVAHAK_")) {
        throw new IllegalStateException("test Keycloak realm contains unresolved credentials");
      }
      Path testRealm = Files.createTempFile("ayuvahak-keycloak-test-realm-", ".json");
      Files.writeString(testRealm, realm);
      testRealm.toFile().deleteOnExit();
      return testRealm;
    } catch (IOException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }
}
