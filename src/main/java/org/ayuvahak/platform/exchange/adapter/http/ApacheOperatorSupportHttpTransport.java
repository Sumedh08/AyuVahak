package org.ayuvahak.platform.exchange.adapter.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.operatorsupport.application.OperatorSupportHttpTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Vetted, pinned-DNS HTTPS transport for the configured documentation-support provider only. */
@Component
final class ApacheOperatorSupportHttpTransport implements OperatorSupportHttpTransport {

  private static final PartnerKey PROFILE_KEY = new PartnerKey("operator-support");
  private static final int MAX_REQUEST_BYTES = 32_768;

  private final OutboundAddressPolicy addressPolicy;
  private final VettedDnsResolver.AddressLookup addressLookup;
  private final BoundedPartnerOperationExecutor operations;

  @Autowired
  ApacheOperatorSupportHttpTransport(
      BoundedDnsAddressLookup addressLookup,
      @Qualifier("operatorSupportOperationExecutor") BoundedPartnerOperationExecutor operations) {
    this(new EgressAddressPolicy(Set.of(443), Map.of()), addressLookup, operations);
  }

  ApacheOperatorSupportHttpTransport(
      OutboundAddressPolicy addressPolicy,
      VettedDnsResolver.AddressLookup addressLookup,
      BoundedPartnerOperationExecutor operations) {
    this.addressPolicy = Objects.requireNonNull(addressPolicy, "address policy");
    this.addressLookup = Objects.requireNonNull(addressLookup, "address lookup");
    this.operations = Objects.requireNonNull(operations, "operation executor");
  }

  @Override
  public byte[] postJson(
      URI configuredEndpoint,
      byte[] requestBody,
      char[] bearerCredential,
      Duration timeout,
      int maximumResponseBytes) {
    validateRequest(
        configuredEndpoint, requestBody, bearerCredential, timeout, maximumResponseBytes);
    int port = configuredEndpoint.getPort() == -1 ? 443 : configuredEndpoint.getPort();
    VettedDnsResolver resolver;
    SSLContext sslContext;
    try {
      resolver =
          new VettedDnsResolver(
              configuredEndpoint.getHost(),
              PROFILE_KEY,
              port,
              Duration.ofSeconds(2),
              addressPolicy,
              addressLookup);
      sslContext = SSLContext.getDefault();
    } catch (RuntimeException | GeneralSecurityException failure) {
      throw unavailable();
    }
    int timeoutMillis = Math.toIntExact(timeout.toMillis());
    ConnectionConfig connectionConfig =
        ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(timeoutMillis))
            .setSocketTimeout(Timeout.ofMilliseconds(timeoutMillis))
            .build();
    PoolingHttpClientConnectionManager manager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver)
            .setTlsSocketStrategy(
                ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext)
                    .setHostnameVerifier(new DefaultHostnameVerifier())
                    .buildClassic())
            .setMaxConnTotal(1)
            .setMaxConnPerRoute(1)
            .setDefaultConnectionConfig(connectionConfig)
            .setDefaultSocketConfig(
                SocketConfig.custom().setSoTimeout(Timeout.ofMilliseconds(timeoutMillis)).build())
            .build();
    var request = new org.apache.hc.client5.http.classic.methods.HttpPost(configuredEndpoint);
    request.setHeader("Accept", "application/json");
    request.setHeader("Accept-Encoding", "identity");
    request.setHeader("Content-Type", "application/json");
    request.setHeader("Authorization", "Bearer " + new String(bearerCredential));
    request.setEntity(
        new ByteArrayEntity(
            requestBody.clone(),
            ContentType.APPLICATION_JSON.withCharset(java.nio.charset.StandardCharsets.UTF_8)));
    HttpClientContext context = HttpClientContext.create();
    try (manager;
        CloseableHttpClient client =
            HttpClients.custom()
                .setConnectionManager(manager)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .disableDefaultUserAgent()
                .setDefaultRequestConfig(requestConfig(timeoutMillis))
                .build()) {
      return operations.execute(
          () ->
              client.execute(
                  request,
                  context,
                  response -> responseBody(response, context, resolver, maximumResponseBytes)),
          timeout,
          () -> {
            request.cancel();
            try {
              client.close();
            } catch (IOException ignored) {
              // The provider call is already cancelled; do not log transport internals.
            }
          });
    } catch (PartnerTransportException exception) {
      throw unavailable();
    } catch (Exception exception) {
      throw unavailable();
    }
  }

  private static byte[] responseBody(
      ClassicHttpResponse response,
      HttpClientContext context,
      VettedDnsResolver resolver,
      int maximumBytes)
      throws IOException {
    verifyPeer(context, resolver);
    if (response.getCode() != 200) {
      throw unavailable();
    }
    String contentType = header(response, "Content-Type");
    if (contentType == null || !"application/json".equals(mediaType(contentType))) {
      throw unavailable();
    }
    String contentEncoding = header(response, "Content-Encoding");
    if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding.strip())) {
      throw unavailable();
    }
    HttpEntity entity = response.getEntity();
    if (entity == null || entity.getContentLength() > maximumBytes) {
      throw unavailable();
    }
    try (InputStream input = entity.getContent()) {
      return readBounded(input, maximumBytes);
    } catch (ResponseTooLargeException tooLarge) {
      throw unavailable();
    }
  }

  private static void validateRequest(
      URI endpoint,
      byte[] requestBody,
      char[] bearerCredential,
      Duration timeout,
      int maximumResponseBytes) {
    if (endpoint == null
        || !"https".equalsIgnoreCase(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null
        || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
        || !"/v1/chat/completions".equals(endpoint.getPath())
        || requestBody == null
        || requestBody.length == 0
        || requestBody.length > MAX_REQUEST_BYTES
        || bearerCredential == null
        || bearerCredential.length == 0
        || timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || maximumResponseBytes < 1
        || maximumResponseBytes > 65_536) {
      throw unavailable();
    }
  }

  private static RequestConfig requestConfig(int timeoutMillis) {
    return RequestConfig.custom()
        .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMillis))
        .setConnectTimeout(Timeout.ofMilliseconds(timeoutMillis))
        .setResponseTimeout(Timeout.ofMilliseconds(timeoutMillis))
        .setRedirectsEnabled(false)
        .setAuthenticationEnabled(false)
        .setContentCompressionEnabled(false)
        .setHardCancellationEnabled(true)
        .build();
  }

  private static void verifyPeer(HttpClientContext context, VettedDnsResolver resolver) {
    if (context.getEndpointDetails() == null) {
      throw unavailable();
    }
    SocketAddress remote = context.getEndpointDetails().getRemoteAddress();
    if (!(remote instanceof InetSocketAddress socketAddress)
        || !resolver.isVettedPeer(socketAddress.getAddress())) {
      throw unavailable();
    }
  }

  private static String header(ClassicHttpResponse response, String name) {
    Header header = response.getFirstHeader(name);
    return header == null ? null : header.getValue();
  }

  private static String mediaType(String contentType) {
    int semicolon = contentType.indexOf(';');
    return (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
        .strip()
        .toLowerCase(java.util.Locale.ROOT);
  }

  private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8_192));
    byte[] buffer = new byte[4_096];
    int total = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (read == 0) {
        continue;
      }
      if (read > maximumBytes - total) {
        throw new ResponseTooLargeException();
      }
      output.write(buffer, 0, read);
      total += read;
    }
    return output.toByteArray();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Configured operator support provider is unavailable.");
  }

  private static final class ResponseTooLargeException extends IOException {}
}
