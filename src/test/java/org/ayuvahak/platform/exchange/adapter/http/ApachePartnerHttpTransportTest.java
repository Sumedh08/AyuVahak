package org.ayuvahak.platform.exchange.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadRequest;
import org.ayuvahak.platform.exchange.application.PartnerTokenEndpointRequest;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.junit.jupiter.api.Test;

class ApachePartnerHttpTransportTest {

  private static final PartnerKey PARTNER = new PartnerKey("SYNTHETIC-DIRECTORY");
  private static final PartnerTrustConfiguration.AllowedOperation OPERATION =
      new PartnerTrustConfiguration.AllowedOperation(
          PartnerTrustConfiguration.OwnerContext.DIRECTORY,
          "mcsd.iti90.search",
          PartnerTrustConfiguration.Direction.OUTBOUND,
          PartnerTrustConfiguration.Protocol.FHIR_R4_JSON,
          "IHE-mCSD");

  private static final PartnerTrustConfiguration.AllowedOperation TRANSPORT_DISPATCH =
      new PartnerTrustConfiguration.AllowedOperation(
          PartnerTrustConfiguration.OwnerContext.TRANSPORT,
          "transport.dispatch",
          PartnerTrustConfiguration.Direction.OUTBOUND,
          PartnerTrustConfiguration.Protocol.JSON,
          "AyuVahakTransportProvider-v1");

  @Test
  void sendsOnlyTheConfiguredBoundedJsonOperationToTheVettedTlsPartner() throws Exception {
    AtomicReference<String> method = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    AtomicReference<String> operationReference = new AtomicReference<>();
    AtomicReference<String> contentType = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> {
                  method.set(exchange.getRequestMethod());
                  path.set(exchange.getRequestURI().toASCIIString());
                  operationReference.set(
                      exchange.getRequestHeaders().getFirst("X-AyuVahak-Operation-Reference"));
                  contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                  requestBody.set(
                      new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                  return new LocalResponse(
                      202,
                      "application/json",
                      "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8));
                });
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);
      var response =
          transport.execute(
              transportProfile("partner.test", partner.port()),
              new PartnerOutboundOperationRequest(
                  PARTNER,
                  TRANSPORT_DISPATCH,
                  List.of("transport", "requests"),
                  "{\"transportRequestId\":\"SYN-TRANSPORT-001\"}".getBytes(StandardCharsets.UTF_8),
                  "dispatch-SYN-TRANSPORT-001"),
              Optional.empty(),
              () -> {});

      assertThat(method).hasValue("POST");
      assertThat(path).hasValue("/api/transport/requests");
      assertThat(operationReference).hasValue("dispatch-SYN-TRANSPORT-001");
      assertThat(contentType).hasValue("application/json");
      assertThat(requestBody).hasValue("{\"transportRequestId\":\"SYN-TRANSPORT-001\"}");
      assertThat(response.status()).isEqualTo(202);
      assertThat(new String(response.body(), StandardCharsets.UTF_8))
          .isEqualTo("{\"accepted\":true}");
      assertThat(partner.requests()).hasValue(1);
    }
  }

  @Test
  void acceptsAnEmptySuccessfulOperationResponseWithoutAContentType() throws Exception {
    try (LocalPartner partner =
            LocalPartner.start(exchange -> new LocalResponse(202, null, new byte[0]));
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);
      var response =
          transport.execute(
              transportProfile("partner.test", partner.port()),
              new PartnerOutboundOperationRequest(
                  PARTNER,
                  TRANSPORT_DISPATCH,
                  List.of("transport", "requests"),
                  "{}".getBytes(StandardCharsets.UTF_8),
                  "dispatch-SYN-TRANSPORT-EMPTY-RESPONSE"),
              Optional.empty(),
              () -> {});

      assertThat(response.status()).isEqualTo(202);
      assertThat(response.contentType()).isEmpty();
      assertThat(response.body()).isEmpty();
      assertThat(partner.requests()).hasValue(1);
    }
  }

  @Test
  void refusesRedirectsForTransportCommandsAndDoesNotRetryProviderSideEffects() throws Exception {
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> {
                  exchange
                      .getResponseHeaders()
                      .add("Location", "https://other.partner.test/dispatch");
                  return new LocalResponse(302, "application/json", new byte[0]);
                });
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);
      var profile = transportProfile("partner.test", partner.port());
      var request =
          new PartnerOutboundOperationRequest(
              PARTNER,
              TRANSPORT_DISPATCH,
              List.of("transport", "requests"),
              "{}".getBytes(StandardCharsets.UTF_8),
              "dispatch-SYN-TRANSPORT-002");

      assertThatThrownBy(() -> transport.execute(profile, request, Optional.empty(), () -> {}))
          .isInstanceOfSatisfying(
              PartnerTransportException.class,
              failure ->
                  assertThat(failure.failure())
                      .isEqualTo(PartnerTransportFailure.INVALID_RESPONSE));
      assertThat(partner.requests()).hasValue(1);
    }
  }

  @Test
  void pinsSocketToTheOneValidatedAddressAndPreservesHostnameVerification() throws Exception {
    AtomicInteger dnsLookups = new AtomicInteger();
    AtomicReference<String> observedPath = new AtomicReference<>();
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> {
                  observedPath.set(exchange.getRequestURI().toASCIIString());
                  return new LocalResponse(
                      200,
                      "application/fhir+json",
                      "{\"resourceType\":\"Bundle\"}".getBytes(StandardCharsets.UTF_8));
                });
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport =
          new ApachePartnerHttpTransport(
              new LocalTestOutboundAddressPolicy(partner.port()),
              (hostname, timeout) -> {
                int lookup = dnsLookups.getAndIncrement();
                byte[] answer =
                    lookup == 0
                        ? new byte[] {127, 0, 0, 1}
                        : new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254};
                return new InetAddress[] {InetAddress.getByAddress(hostname, answer)};
              },
              configuration -> partner.clientTlsContext(),
              executor);

      var response =
          transport.execute(
              profile("partner.test", partner.port(), limits(2_000, 100_000, 200_000)), request());

      assertThat(response.status()).isEqualTo(200);
      assertThat(new String(response.body(), StandardCharsets.UTF_8))
          .isEqualTo("{\"resourceType\":\"Bundle\"}");
      assertThat(observedPath).hasValue("/fhir/Organization?active=true");
      assertThat(dnsLookups).hasValue(1);
      assertThat(partner.requests()).hasValue(1);
    }
  }

  @Test
  void doesNotFollowRedirects() throws Exception {
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> {
                  exchange
                      .getResponseHeaders()
                      .add("Location", "https://other.partner.test/fhir/Patient");
                  return new LocalResponse(302, "text/plain", new byte[0]);
                });
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);

      assertThatThrownBy(
              () ->
                  transport.execute(
                      profile("partner.test", partner.port(), limits(2_000, 100_000, 200_000)),
                      request()))
          .isInstanceOfSatisfying(
              PartnerTransportException.class,
              failure ->
                  assertThat(failure.failure())
                      .isEqualTo(PartnerTransportFailure.INVALID_RESPONSE));
      assertThat(partner.requests()).hasValue(1);
    }
  }

  @Test
  void keepsRemoteAuthenticationAuthorizationRateLimitAndOtherClientFailuresDistinct()
      throws Exception {
    AtomicInteger status = new AtomicInteger(401);
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> {
                  exchange.getResponseHeaders().add("Retry-After", "7");
                  return new LocalResponse(status.get(), "application/fhir+json", new byte[0]);
                });
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);
      PartnerProfileVersion profile =
          profile("partner.test", partner.port(), limits(2_000, 100_000, 200_000));

      assertTransportFailure(
          transport, profile, PartnerTransportFailure.REMOTE_AUTHENTICATION_FAILURE);
      status.set(403);
      assertTransportFailure(
          transport, profile, PartnerTransportFailure.REMOTE_AUTHORIZATION_FAILURE);
      status.set(429);
      assertThatThrownBy(() -> transport.execute(profile, request()))
          .isInstanceOfSatisfying(
              PartnerTransportException.class,
              failure -> {
                assertThat(failure.failure()).isEqualTo(PartnerTransportFailure.RATE_LIMITED);
                assertThat(failure.retryAfter()).contains(java.time.Duration.ofSeconds(7));
              });
      status.set(422);
      assertTransportFailure(transport, profile, PartnerTransportFailure.REMOTE_4XX);
    }
  }

  private static void assertTransportFailure(
      ApachePartnerHttpTransport transport,
      PartnerProfileVersion profile,
      PartnerTransportFailure expected) {
    assertThatThrownBy(() -> transport.execute(profile, request()))
        .isInstanceOfSatisfying(
            PartnerTransportException.class,
            failure -> assertThat(failure.failure()).isEqualTo(expected));
  }

  @Test
  void propagatesSafeCorrelationIdentifierToConfiguredPartnerRead() throws Exception {
    AtomicReference<String> observedCorrelation = new AtomicReference<>();
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> {
                  observedCorrelation.set(
                      exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
                  return new LocalResponse(
                      200, "application/fhir+json", "{}".getBytes(StandardCharsets.UTF_8));
                });
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);
      PartnerOutboundReadRequest request =
          new PartnerOutboundReadRequest(
              PARTNER,
              OPERATION,
              List.of("Organization"),
              Map.of("active", List.of("true")),
              new CorrelationId("synthetic-correlation-9"));

      transport.execute(
          profile("partner.test", partner.port(), limits(2_000, 100_000, 200_000)), request);

      assertThat(observedCorrelation).hasValue("synthetic-correlation-9");
      assertThat(partner.requests()).hasValue(1);
    }
  }

  @Test
  void rejectsOversizedResponseWithoutReturningItsBody() throws Exception {
    try (LocalPartner partner =
            LocalPartner.start(
                exchange ->
                    new LocalResponse(
                        200,
                        "application/fhir+json",
                        "synthetic-response-that-is-too-large".getBytes(StandardCharsets.UTF_8)));
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);

      assertThatThrownBy(
              () ->
                  transport.execute(
                      profile("partner.test", partner.port(), limits(2_000, 16, 32)), request()))
          .isInstanceOfSatisfying(
              PartnerTransportException.class,
              failure ->
                  assertThat(failure.failure())
                      .isEqualTo(PartnerTransportFailure.RESPONSE_TOO_LARGE));
    }
  }

  @Test
  void rejectsGzipExpansionPastTheDecompressedLimit() throws Exception {
    byte[] compressed =
        gzip("synthetic-expanded-response-over-limit".getBytes(StandardCharsets.UTF_8));
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> new LocalResponse(200, "application/fhir+json", compressed, "gzip"));
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);

      assertThatThrownBy(
              () ->
                  transport.execute(
                      profile("partner.test", partner.port(), limits(2_000, 1_000, 8)), request()))
          .isInstanceOfSatisfying(
              PartnerTransportException.class,
              failure ->
                  assertThat(failure.failure())
                      .isEqualTo(PartnerTransportFailure.RESPONSE_TOO_LARGE));
    }
  }

  @Test
  void rejectsTlsCertificateForAHostOtherThanTheConfiguredHostname() throws Exception {
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> new LocalResponse(200, "application/fhir+json", new byte[0]));
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);

      assertThatThrownBy(
              () ->
                  transport.execute(
                      profile(
                          "wrong.partner.test", partner.port(), limits(2_000, 100_000, 200_000)),
                      request()))
          .isInstanceOfSatisfying(
              PartnerTransportException.class,
              failure ->
                  assertThat(failure.failure()).isEqualTo(PartnerTransportFailure.TLS_FAILURE));
      assertThat(partner.requests()).hasValue(0);
    }
  }

  @Test
  void appliesWholeOperationDeadlineToSlowLocalPeer() throws Exception {
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> {
                  Thread.sleep(2_000);
                  return new LocalResponse(200, "application/fhir+json", new byte[0]);
                });
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);

      assertThatThrownBy(
              () ->
                  transport.execute(
                      profile("partner.test", partner.port(), limits(300, 100_000, 200_000)),
                      request()))
          .isInstanceOfSatisfying(
              PartnerTransportException.class,
              failure ->
                  assertThat(failure.failure())
                      .isEqualTo(PartnerTransportFailure.OPERATION_TIMEOUT));
    }
  }

  @Test
  void postsClientCredentialsOnlyToTheConfiguredPinnedTokenEndpoint() throws Exception {
    AtomicReference<String> observedMethod = new AtomicReference<>();
    AtomicReference<String> observedPath = new AtomicReference<>();
    AtomicReference<String> observedContentType = new AtomicReference<>();
    AtomicReference<String> observedAuthorization = new AtomicReference<>();
    AtomicReference<String> observedBody = new AtomicReference<>();
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> {
                  observedMethod.set(exchange.getRequestMethod());
                  observedPath.set(exchange.getRequestURI().toASCIIString());
                  observedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                  observedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                  observedBody.set(
                      new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                  return new LocalResponse(
                      200,
                      "application/json",
                      "{\"access_token\":\"synthetic-token\"}".getBytes(StandardCharsets.UTF_8));
                });
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);

      var response =
          transport.requestToken(
              tokenProfile(
                  "partner.test",
                  partner.port(),
                  PartnerTrustConfiguration.AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS),
              PartnerTokenEndpointRequest.clientSecretBasic(
                  "synthetic-client", "synthetic-secret".toCharArray()));

      assertThat(response.status()).isEqualTo(200);
      assertThat(new String(response.body(), StandardCharsets.UTF_8))
          .isEqualTo("{\"access_token\":\"synthetic-token\"}");
      assertThat(observedMethod).hasValue("POST");
      assertThat(observedPath).hasValue("/oauth/token");
      assertThat(observedContentType)
          .hasValueSatisfying(
              value -> assertThat(value).startsWith("application/x-www-form-urlencoded"));
      assertThat(observedAuthorization)
          .hasValueSatisfying(value -> assertThat(value).startsWith("Basic "));
      assertThat(observedBody).hasValue("grant_type=client_credentials");
      assertThat(partner.requests()).hasValue(1);
    }
  }

  @Test
  void doesNotFollowTokenEndpointRedirectsOrReturnErrorBodies() throws Exception {
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> {
                  exchange.getResponseHeaders().add("Location", "https://other.test/token");
                  return new LocalResponse(
                      302,
                      "application/json",
                      "synthetic-secret-body".getBytes(StandardCharsets.UTF_8));
                });
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);

      var response =
          transport.requestToken(
              tokenProfile(
                  "partner.test",
                  partner.port(),
                  PartnerTrustConfiguration.AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS),
              PartnerTokenEndpointRequest.clientSecretBasic(
                  "synthetic-client", "synthetic-secret".toCharArray()));

      assertThat(response.status()).isEqualTo(302);
      assertThat(response.body()).isEmpty();
      assertThat(partner.requests()).hasValue(1);
    }
  }

  @Test
  void rejectsTokenRequestUsingTheWrongConfiguredClientAuthentication() throws Exception {
    try (LocalPartner partner =
            LocalPartner.start(
                exchange -> new LocalResponse(200, "application/json", new byte[0]));
        BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(2, 8)) {
      ApachePartnerHttpTransport transport = localTransport(partner, executor, null);

      assertThatThrownBy(
              () ->
                  transport.requestToken(
                      tokenProfile(
                          "partner.test",
                          partner.port(),
                          PartnerTrustConfiguration.AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS),
                      PartnerTokenEndpointRequest.privateKeyJwt(
                          "synthetic-client", "header.payload.signature")))
          .isInstanceOfSatisfying(
              PartnerTransportException.class,
              failure ->
                  assertThat(failure.failure())
                      .isEqualTo(PartnerTransportFailure.AUTHENTICATION_NOT_SUPPORTED));
      assertThat(partner.requests()).hasValue(0);
    }
  }

  private static ApachePartnerHttpTransport localTransport(
      LocalPartner partner, BoundedPartnerOperationExecutor executor, AtomicInteger dnsLookups) {
    return new ApachePartnerHttpTransport(
        new LocalTestOutboundAddressPolicy(partner.port()),
        (hostname, timeout) -> {
          if (dnsLookups != null) {
            dnsLookups.incrementAndGet();
          }
          return new InetAddress[] {InetAddress.getByAddress(hostname, new byte[] {127, 0, 0, 1})};
        },
        configuration -> partner.clientTlsContext(),
        executor);
  }

  private static PartnerProfileVersion profile(
      String hostname, int port, PartnerTrustConfiguration.Limits limits) {
    PartnerTrustConfiguration configuration =
        new PartnerTrustConfiguration(
            PARTNER,
            new ExternalReference(
                new IssuerId("urn:synthetic:directory"), "Organization", "SYN-ORG-001"),
            "https://" + hostname + ":" + port,
            "/fhir",
            PartnerTrustConfiguration.Authentication.none(),
            PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            limits,
            PartnerTrustConfiguration.RetryClass.IDEMPOTENT_READ,
            List.of(OPERATION));
    Instant now = Instant.now();
    return new PartnerProfileVersion(
        InternalId.generate(), 1, PartnerProfileState.ACTIVE, configuration, now, now);
  }

  private static PartnerProfileVersion transportProfile(String hostname, int port) {
    PartnerTrustConfiguration configuration =
        new PartnerTrustConfiguration(
            PARTNER,
            new ExternalReference(
                new IssuerId("urn:synthetic:directory"), "Organization", "SYN-ORG-001"),
            "https://" + hostname + ":" + port,
            "/api",
            PartnerTrustConfiguration.Authentication.none(),
            PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            limits(2_000, 100_000, 200_000),
            PartnerTrustConfiguration.RetryClass.NEVER,
            List.of(TRANSPORT_DISPATCH));
    Instant now = Instant.now();
    return new PartnerProfileVersion(
        InternalId.generate(), 1, PartnerProfileState.ACTIVE, configuration, now, now);
  }

  private static PartnerProfileVersion tokenProfile(
      String hostname,
      int port,
      PartnerTrustConfiguration.AuthenticationMethod authenticationMethod) {
    PartnerTrustConfiguration configuration =
        new PartnerTrustConfiguration(
            PARTNER,
            new ExternalReference(
                new IssuerId("urn:synthetic:directory"), "Organization", "SYN-ORG-001"),
            "https://" + hostname + ":" + port,
            "/fhir",
            new PartnerTrustConfiguration.Authentication(
                authenticationMethod,
                Optional.of("env:SYNTHETIC_CLIENT_CREDENTIAL"),
                Optional.of("env:SYNTHETIC_TOKEN_SIGNING_KEYS"),
                Optional.of("https://" + hostname + ":" + port),
                Optional.of("/oauth/token"),
                Optional.of("https://issuer.test"),
                Optional.of("synthetic-resource"),
                Optional.of("synthetic-client")),
            PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            limits(2_000, 100_000, 200_000),
            PartnerTrustConfiguration.RetryClass.IDEMPOTENT_READ,
            List.of(OPERATION));
    Instant now = Instant.now();
    return new PartnerProfileVersion(
        InternalId.generate(), 1, PartnerProfileState.ACTIVE, configuration, now, now);
  }

  private static PartnerOutboundReadRequest request() {
    return new PartnerOutboundReadRequest(
        PARTNER, OPERATION, List.of("Organization"), Map.of("active", List.of("true")));
  }

  private static PartnerTrustConfiguration.Limits limits(
      int operationTimeoutMillis, int maxResponseBytes, int maxDecompressedBytes) {
    return new PartnerTrustConfiguration.Limits(
        1_000,
        2_000,
        operationTimeoutMillis,
        maxResponseBytes,
        maxDecompressedBytes,
        5,
        100,
        20,
        1);
  }

  private static byte[] gzip(byte[] value) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(value);
    }
    return output.toByteArray();
  }

  @FunctionalInterface
  private interface LocalHandler {
    LocalResponse respond(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
  }

  private record LocalResponse(
      int status, String contentType, byte[] body, String contentEncoding) {
    private LocalResponse(int status, String contentType, byte[] body) {
      this(status, contentType, body, null);
    }
  }

  private static final class LocalPartner implements AutoCloseable {

    private static final char[] STORE_PASSWORD = "local-test-only".toCharArray();
    private final HttpsServer server;
    private final SSLContext clientContext;
    private final AtomicInteger requestCounter;

    static LocalPartner start(LocalHandler handler) throws Exception {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (InputStream input =
          ApachePartnerHttpTransportTest.class.getResourceAsStream(
              "/exchange/synthetic-partner.p12")) {
        if (input == null) {
          throw new IllegalStateException("Synthetic local TLS fixture is missing.");
        }
        keyStore.load(input, STORE_PASSWORD);
      }
      KeyManagerFactory keyManagers =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagers.init(keyStore, STORE_PASSWORD);
      SSLContext serverContext = SSLContext.getInstance("TLS");
      serverContext.init(keyManagers.getKeyManagers(), null, null);

      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustStore.load(null, null);
      trustStore.setCertificateEntry(
          "synthetic-partner", keyStore.getCertificate("synthetic-partner"));
      TrustManagerFactory trustManagers =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagers.init(trustStore);
      SSLContext clientContext = SSLContext.getInstance("TLS");
      clientContext.init(null, trustManagers.getTrustManagers(), null);

      HttpsServer server =
          HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
      server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
      AtomicInteger requests = new AtomicInteger();
      server.createContext(
          "/",
          exchange -> {
            requests.incrementAndGet();
            try {
              LocalResponse response = handler.respond(exchange);
              if (response.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", response.contentType());
              }
              if (response.contentEncoding() != null) {
                exchange.getResponseHeaders().set("Content-Encoding", response.contentEncoding());
              }
              exchange.sendResponseHeaders(response.status(), response.body().length);
              try (var output = exchange.getResponseBody()) {
                output.write(response.body());
              }
            } catch (Exception exception) {
              exchange.close();
            }
          });
      server.start();
      return new LocalPartner(server, clientContext, requests);
    }

    private LocalPartner(
        HttpsServer server, SSLContext clientContext, AtomicInteger requestCounter) {
      this.server = server;
      this.clientContext = clientContext;
      this.requestCounter = requestCounter;
    }

    int port() {
      return server.getAddress().getPort();
    }

    SSLContext clientTlsContext() {
      return clientContext;
    }

    AtomicInteger requests() {
      return requestCounter;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static final class LocalTestOutboundAddressPolicy implements OutboundAddressPolicy {

    private final int port;

    private LocalTestOutboundAddressPolicy(int port) {
      this.port = port;
    }

    @Override
    public void validatePort(PartnerKey partnerKey, int candidatePort) {
      if (!PARTNER.equals(partnerKey) || candidatePort != port) {
        throw new UnsafeOutboundDestinationException(
            UnsafeOutboundDestinationException.Reason.PORT_NOT_ALLOWED);
      }
    }

    @Override
    public List<InetAddress> validate(
        PartnerKey partnerKey, int candidatePort, List<InetAddress> answers) {
      validatePort(partnerKey, candidatePort);
      if (answers == null
          || answers.isEmpty()
          || answers.stream().anyMatch(address -> !address.getHostAddress().equals("127.0.0.1"))) {
        throw new UnsafeOutboundDestinationException(
            UnsafeOutboundDestinationException.Reason.FORBIDDEN_ADDRESS);
      }
      return List.copyOf(answers);
    }
  }
}
