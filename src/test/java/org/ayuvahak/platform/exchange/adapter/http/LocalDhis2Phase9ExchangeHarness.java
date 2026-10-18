package org.ayuvahak.platform.exchange.adapter.http;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.PartnerAuthorizationPort;
import org.ayuvahak.platform.exchange.application.PartnerBearerToken;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationService;
import org.ayuvahak.platform.exchange.application.PartnerTrustProfileStore;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.TlsPolicy;

/** Local-only HTTPS DHIS2-shaped peer wired through the production Phase 9 outbound service. */
public final class LocalDhis2Phase9ExchangeHarness implements AutoCloseable {

  private static final String HOST = "partner.test";
  private static final String SUCCESS_SUMMARY =
      "{\"status\":\"SUCCESS\",\"importCount\":{\"imported\":1,\"updated\":0,"
          + "\"ignored\":0,\"deleted\":0}}";
  private static final byte[] LOOPBACK = {127, 0, 0, 1};

  private final HttpsServer server;
  private final BoundedPartnerOperationExecutor executor;
  private final PartnerOutboundOperationPort exchange;
  private final AtomicInteger requests;
  private final AtomicReference<String> method;
  private final AtomicReference<String> path;
  private final AtomicReference<String> operationReference;
  private final AtomicReference<String> requestBody;

  public static LocalDhis2Phase9ExchangeHarness start(PartnerKey partnerKey) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    char[] password = "local-test-only".toCharArray();
    try (InputStream input =
        LocalDhis2Phase9ExchangeHarness.class.getResourceAsStream(
            "/exchange/synthetic-partner.p12")) {
      if (input == null) {
        throw new IllegalStateException("Synthetic local TLS fixture is missing.");
      }
      keyStore.load(input, password);
    }

    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, password);
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

    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> method = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    AtomicReference<String> operationReference = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpsServer server =
        HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
    server.createContext(
        "/",
        request -> {
          requests.incrementAndGet();
          method.set(request.getRequestMethod());
          path.set(request.getRequestURI().toASCIIString());
          operationReference.set(
              request.getRequestHeaders().getFirst("X-AyuVahak-Operation-Reference"));
          requestBody.set(
              new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response = SUCCESS_SUMMARY.getBytes(StandardCharsets.UTF_8);
          request.getResponseHeaders().set("Content-Type", "application/json");
          request.sendResponseHeaders(200, response.length);
          try (var output = request.getResponseBody()) {
            output.write(response);
          }
        });
    server.start();

    BoundedPartnerOperationExecutor executor = new BoundedPartnerOperationExecutor(1, 2);
    try {
      PartnerProfileVersion profile = profile(partnerKey, server.getAddress().getPort());
      PartnerTrustProfileStore profiles = activeProfileStore(profile);
      PartnerAuthorizationPort authorization =
          new PartnerAuthorizationPort() {
            @Override
            public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion activeProfile) {
              return Optional.empty();
            }

            @Override
            public void invalidate(PartnerKey key) {}
          };
      ApachePartnerHttpTransport transport =
          new ApachePartnerHttpTransport(
              localAddressPolicy(partnerKey, server.getAddress().getPort()),
              LocalDhis2Phase9ExchangeHarness::resolveOnlyLocalTestHost,
              configuration -> {
                if (configuration.tlsPolicy() != TlsPolicy.SYSTEM_CA) {
                  throw new IllegalStateException("Local test profile requires verified TLS.");
                }
                return clientContext;
              },
              executor);
      PartnerOutboundOperationPort exchange =
          new PartnerOutboundOperationService(profiles, authorization, transport);
      return new LocalDhis2Phase9ExchangeHarness(
          server, executor, exchange, requests, method, path, operationReference, requestBody);
    } catch (RuntimeException | Error failure) {
      executor.close();
      server.stop(0);
      throw failure;
    }
  }

  private LocalDhis2Phase9ExchangeHarness(
      HttpsServer server,
      BoundedPartnerOperationExecutor executor,
      PartnerOutboundOperationPort exchange,
      AtomicInteger requests,
      AtomicReference<String> method,
      AtomicReference<String> path,
      AtomicReference<String> operationReference,
      AtomicReference<String> requestBody) {
    this.server = server;
    this.executor = executor;
    this.exchange = exchange;
    this.requests = requests;
    this.method = method;
    this.path = path;
    this.operationReference = operationReference;
    this.requestBody = requestBody;
  }

  public PartnerOutboundOperationPort exchange() {
    return exchange;
  }

  public int requestCount() {
    return requests.get();
  }

  public String method() {
    return method.get();
  }

  public String path() {
    return path.get();
  }

  public String operationReference() {
    return operationReference.get();
  }

  public String requestBody() {
    return requestBody.get();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.close();
  }

  private static PartnerProfileVersion profile(PartnerKey partnerKey, int port) {
    AllowedOperation operation = AllowedOperation.analyticsDhis2DataValueSet();
    PartnerTrustConfiguration configuration =
        new PartnerTrustConfiguration(
            partnerKey,
            new ExternalReference(
                new IssuerId("urn:synthetic:analytics"), "Organization", "SYN-ORG-001"),
            "https://" + HOST + ":" + port,
            "/api",
            PartnerTrustConfiguration.Authentication.none(),
            TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new PartnerTrustConfiguration.Limits(
                1_000, 2_000, 10_000, 100_000, 100_000, 1, 10, 5, 1),
            PartnerTrustConfiguration.RetryClass.NEVER,
            List.of(operation));
    Instant now = Instant.parse("2026-10-19T00:00:00Z");
    return new PartnerProfileVersion(
        InternalId.generate(), 1, PartnerProfileState.ACTIVE, configuration, now, now);
  }

  private static PartnerTrustProfileStore activeProfileStore(PartnerProfileVersion profile) {
    return (PartnerTrustProfileStore)
        java.lang.reflect.Proxy.newProxyInstance(
            PartnerTrustProfileStore.class.getClassLoader(),
            new Class<?>[] {PartnerTrustProfileStore.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("findActive")
                  && arguments[0].equals(profile.configuration().partnerKey())
                  && arguments[1].equals(AllowedOperation.analyticsDhis2DataValueSet())) {
                return Optional.of(profile);
              }
              throw new UnsupportedOperationException("unexpected local trust-profile operation");
            });
  }

  private static OutboundAddressPolicy localAddressPolicy(
      PartnerKey allowedPartner, int allowedPort) {
    return new OutboundAddressPolicy() {
      @Override
      public void validatePort(PartnerKey partnerKey, int port) {
        if (!allowedPartner.equals(partnerKey) || allowedPort != port) {
          throw new UnsafeOutboundDestinationException(
              UnsafeOutboundDestinationException.Reason.PORT_NOT_ALLOWED);
        }
      }

      @Override
      public List<InetAddress> validate(
          PartnerKey partnerKey, int port, List<InetAddress> answers) {
        validatePort(partnerKey, port);
        if (answers == null
            || answers.isEmpty()
            || answers.stream()
                .anyMatch(address -> !Arrays.equals(address.getAddress(), LOOPBACK))) {
          throw new UnsafeOutboundDestinationException(
              UnsafeOutboundDestinationException.Reason.FORBIDDEN_ADDRESS);
        }
        return List.copyOf(answers);
      }
    };
  }

  private static InetAddress[] resolveOnlyLocalTestHost(String hostname, java.time.Duration timeout)
      throws java.net.UnknownHostException {
    if (!HOST.equals(hostname)) {
      throw new java.net.UnknownHostException(
          "Only the synthetic local DHIS2 hostname is allowed.");
    }
    return new InetAddress[] {InetAddress.getByAddress(hostname, LOOPBACK)};
  }
}
