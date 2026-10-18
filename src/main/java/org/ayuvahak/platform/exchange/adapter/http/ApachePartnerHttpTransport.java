package org.ayuvahak.platform.exchange.adapter.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
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
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationRequest;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationTransportPort;
import org.ayuvahak.platform.exchange.application.PartnerBearerToken;
import org.ayuvahak.platform.exchange.application.PartnerHttpOperationTransportPort;
import org.ayuvahak.platform.exchange.application.PartnerHttpTransportPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationResponse;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadResponse;
import org.ayuvahak.platform.exchange.application.PartnerTlsContextProvider;
import org.ayuvahak.platform.exchange.application.PartnerTokenEndpointPort;
import org.ayuvahak.platform.exchange.application.PartnerTokenEndpointRequest;
import org.ayuvahak.platform.exchange.application.PartnerTokenEndpointResponse;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;
import org.ayuvahak.platform.exchange.domain.FederationPeerOperations;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.springframework.stereotype.Component;

/** Single-operation Apache HttpClient transport pinned to one fully vetted DNS answer set. */
@Component
final class ApachePartnerHttpTransport
    implements PartnerHttpTransportPort,
        PartnerHttpOperationTransportPort,
        FederationPeerOperationTransportPort,
        PartnerTokenEndpointPort {

  private final OutboundAddressPolicy addressPolicy;
  private final VettedDnsResolver.AddressLookup addressLookup;
  private final PartnerTlsContextProvider tlsContexts;
  private final BoundedPartnerOperationExecutor operations;

  ApachePartnerHttpTransport(
      OutboundAddressPolicy addressPolicy,
      VettedDnsResolver.AddressLookup addressLookup,
      PartnerTlsContextProvider tlsContexts,
      BoundedPartnerOperationExecutor operations) {
    this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
    this.addressLookup = Objects.requireNonNull(addressLookup, "addressLookup");
    this.tlsContexts = Objects.requireNonNull(tlsContexts, "tlsContexts");
    this.operations = Objects.requireNonNull(operations, "operations");
  }

  @Override
  public PartnerOutboundReadResponse execute(
      PartnerProfileVersion activeProfile,
      PartnerOutboundReadRequest request,
      Optional<PartnerBearerToken> bearerToken) {
    return execute(activeProfile, request, bearerToken, () -> {});
  }

  @Override
  public PartnerOutboundOperationResponse execute(
      PartnerProfileVersion activeProfile,
      PartnerOutboundOperationRequest request,
      Optional<PartnerBearerToken> bearerToken,
      Runnable beforeAttempt) {
    Objects.requireNonNull(activeProfile, "activeProfile");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(bearerToken, "bearerToken");
    Objects.requireNonNull(beforeAttempt, "beforeAttempt");
    PartnerTrustConfiguration configuration = activeProfile.configuration();
    if (activeProfile.state() != PartnerProfileState.ACTIVE
        || !configuration.partnerKey().equals(request.partnerKey())
        || !configuration.allowedOperations().contains(request.operation())
        || request.operation().ownerContext() != PartnerTrustConfiguration.OwnerContext.TRANSPORT
        || request.operation().direction() != PartnerTrustConfiguration.Direction.OUTBOUND
        || request.operation().protocol() != PartnerTrustConfiguration.Protocol.JSON
        || configuration.retryClass() != PartnerTrustConfiguration.RetryClass.NEVER) {
      throw new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    }
    PartnerTrustConfiguration.AuthenticationMethod authenticationMethod =
        configuration.authentication().method();
    if (authenticationMethod == PartnerTrustConfiguration.AuthenticationMethod.MUTUAL_TLS
        || (authenticationMethod == PartnerTrustConfiguration.AuthenticationMethod.NONE
            && bearerToken.isPresent())
        || (authenticationMethod != PartnerTrustConfiguration.AuthenticationMethod.NONE
            && bearerToken.isEmpty())) {
      throw new PartnerTransportException(PartnerTransportFailure.AUTHENTICATION_NOT_SUPPORTED);
    }

    URI uri;
    try {
      uri = PartnerRequestUriBuilder.build(configuration, request);
    } catch (IllegalArgumentException exception) {
      throw new PartnerTransportException(PartnerTransportFailure.DESTINATION_REJECTED);
    }
    int port = uri.getPort() < 0 ? 443 : uri.getPort();
    VettedDnsResolver resolver =
        new VettedDnsResolver(
            uri.getHost(),
            configuration.partnerKey(),
            port,
            Duration.ofMillis(configuration.limits().connectTimeoutMillis()),
            addressPolicy,
            addressLookup);
    SSLContext sslContext = tlsContexts.resolve(configuration);
    if (sslContext == null) {
      throw new PartnerTransportException(PartnerTransportFailure.TLS_FAILURE);
    }

    HttpPost outbound = new HttpPost(uri);
    outbound.setHeader("Accept", "application/json");
    outbound.setHeader("Accept-Encoding", "identity");
    outbound.setHeader("Content-Type", "application/json");
    outbound.setHeader("X-AyuVahak-Operation-Reference", request.operationReference());
    outbound.setEntity(
        new ByteArrayEntity(
            request.body(),
            ContentType.APPLICATION_JSON.withCharset(java.nio.charset.StandardCharsets.UTF_8)));
    request
        .correlationId()
        .ifPresent(
            correlationId ->
                outbound.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId.value()));
    bearerToken.ifPresent(
        token -> outbound.setHeader("Authorization", token.authorizationHeader()));
    HttpClientContext context = HttpClientContext.create();
    Duration operationTimeout = Duration.ofMillis(configuration.limits().operationTimeoutMillis());
    PoolingHttpClientConnectionManager manager =
        buildConnectionManager(configuration, resolver, sslContext);
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
                .setDefaultRequestConfig(requestConfig(configuration))
                .build()) {
      return operations.execute(
          () -> {
            beforeAttempt.run();
            return client.execute(
                outbound,
                context,
                response ->
                    handleOperationResponse(
                        response, context, resolver, activeProfile, configuration));
          },
          operationTimeout,
          () -> {
            outbound.cancel();
            try {
              client.close();
            } catch (IOException ignored) {
              // Cancellation already fails closed; transport diagnostics remain sanitized.
            }
          });
    } catch (PartnerTransportException exception) {
      throw exception;
    } catch (Exception exception) {
      throw classify(exception);
    }
  }

  @Override
  public PartnerOutboundOperationResponse execute(
      PartnerProfileVersion activeProfile,
      FederationPeerOperationRequest request,
      Optional<PartnerBearerToken> bearerToken,
      Runnable beforeAttempt) {
    Objects.requireNonNull(activeProfile, "activeProfile");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(bearerToken, "bearerToken");
    Objects.requireNonNull(beforeAttempt, "beforeAttempt");
    PartnerTrustConfiguration configuration = activeProfile.configuration();
    if (activeProfile.state() != PartnerProfileState.ACTIVE
        || activeProfile.version() != request.expectedConfigurationVersion()
        || !configuration.partnerKey().equals(request.partnerKey())
        || !configuration.allowedOperations().contains(request.operation())
        || !FederationPeerOperations.supportsOutbound(request.operation())
        || configuration.federationPeer().isEmpty()
        || !configuration
            .federationPeer()
            .orElseThrow()
            .deploymentId()
            .equals(request.peerDeployment())
        || configuration.retryClass() != PartnerTrustConfiguration.RetryClass.NEVER) {
      throw new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    }
    PartnerTrustConfiguration.AuthenticationMethod authenticationMethod =
        configuration.authentication().method();
    if (authenticationMethod == PartnerTrustConfiguration.AuthenticationMethod.MUTUAL_TLS
        || (authenticationMethod == PartnerTrustConfiguration.AuthenticationMethod.NONE
            && bearerToken.isPresent())
        || (authenticationMethod != PartnerTrustConfiguration.AuthenticationMethod.NONE
            && bearerToken.isEmpty())) {
      throw new PartnerTransportException(PartnerTransportFailure.AUTHENTICATION_NOT_SUPPORTED);
    }

    URI uri;
    try {
      uri = PartnerRequestUriBuilder.build(configuration, request);
    } catch (IllegalArgumentException exception) {
      throw new PartnerTransportException(PartnerTransportFailure.DESTINATION_REJECTED);
    }
    int port = uri.getPort() < 0 ? 443 : uri.getPort();
    VettedDnsResolver resolver =
        new VettedDnsResolver(
            uri.getHost(),
            configuration.partnerKey(),
            port,
            Duration.ofMillis(configuration.limits().connectTimeoutMillis()),
            addressPolicy,
            addressLookup);
    SSLContext sslContext = tlsContexts.resolve(configuration);
    if (sslContext == null) {
      throw new PartnerTransportException(PartnerTransportFailure.TLS_FAILURE);
    }

    HttpPost outbound = new HttpPost(uri);
    outbound.setHeader("Accept", "application/json");
    outbound.setHeader("Accept-Encoding", "identity");
    outbound.setHeader("Content-Type", "application/json");
    outbound.setHeader("X-AyuVahak-Federation-Source", request.sourceDeployment().value());
    outbound.setHeader("X-AyuVahak-Federation-Peer", request.peerDeployment().value());
    outbound.setHeader("X-AyuVahak-Federation-Operation", request.operation().operationCode());
    outbound.setHeader("X-AyuVahak-Operation-Id", request.operationId().toString());
    outbound.setHeader("X-AyuVahak-Operation-Fingerprint", request.fingerprint().value());
    outbound.setHeader(
        "X-AyuVahak-Partner-Configuration-Version",
        Integer.toString(request.expectedConfigurationVersion()));
    outbound.setEntity(
        new ByteArrayEntity(
            request.body(),
            ContentType.APPLICATION_JSON.withCharset(java.nio.charset.StandardCharsets.UTF_8)));
    request
        .correlationId()
        .ifPresent(
            correlationId ->
                outbound.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId.value()));
    bearerToken.ifPresent(
        token -> outbound.setHeader("Authorization", token.authorizationHeader()));
    HttpClientContext context = HttpClientContext.create();
    Duration operationTimeout = Duration.ofMillis(configuration.limits().operationTimeoutMillis());
    PoolingHttpClientConnectionManager manager =
        buildConnectionManager(configuration, resolver, sslContext);
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
                .setDefaultRequestConfig(requestConfig(configuration))
                .build()) {
      return operations.execute(
          () -> {
            beforeAttempt.run();
            return client.execute(
                outbound,
                context,
                response ->
                    handleOperationResponse(
                        response, context, resolver, activeProfile, configuration));
          },
          operationTimeout,
          () -> {
            outbound.cancel();
            try {
              client.close();
            } catch (IOException ignored) {
              // Cancellation already fails closed; transport diagnostics remain sanitized.
            }
          });
    } catch (PartnerTransportException exception) {
      throw exception;
    } catch (Exception exception) {
      throw classify(exception);
    }
  }

  @Override
  public PartnerOutboundReadResponse execute(
      PartnerProfileVersion activeProfile,
      PartnerOutboundReadRequest request,
      Optional<PartnerBearerToken> bearerToken,
      Runnable beforeAttempt) {
    Objects.requireNonNull(activeProfile, "activeProfile");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(bearerToken, "bearerToken");
    Objects.requireNonNull(beforeAttempt, "beforeAttempt");
    PartnerTrustConfiguration configuration = activeProfile.configuration();
    if (activeProfile.state() != PartnerProfileState.ACTIVE
        || !configuration.partnerKey().equals(request.partnerKey())
        || !configuration.allowedOperations().contains(request.operation())
        || request.operation().direction() != PartnerTrustConfiguration.Direction.OUTBOUND) {
      throw new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    }
    PartnerTrustConfiguration.AuthenticationMethod authenticationMethod =
        configuration.authentication().method();
    if (authenticationMethod == PartnerTrustConfiguration.AuthenticationMethod.MUTUAL_TLS
        || (authenticationMethod == PartnerTrustConfiguration.AuthenticationMethod.NONE
            && bearerToken.isPresent())
        || (authenticationMethod != PartnerTrustConfiguration.AuthenticationMethod.NONE
            && bearerToken.isEmpty())) {
      throw new PartnerTransportException(PartnerTransportFailure.AUTHENTICATION_NOT_SUPPORTED);
    }

    URI uri;
    try {
      uri = PartnerRequestUriBuilder.build(configuration, request);
    } catch (IllegalArgumentException exception) {
      throw new PartnerTransportException(PartnerTransportFailure.DESTINATION_REJECTED);
    }

    int port = uri.getPort() < 0 ? 443 : uri.getPort();
    VettedDnsResolver resolver =
        new VettedDnsResolver(
            uri.getHost(),
            configuration.partnerKey(),
            port,
            Duration.ofMillis(configuration.limits().connectTimeoutMillis()),
            addressPolicy,
            addressLookup);
    SSLContext sslContext = tlsContexts.resolve(configuration);
    if (sslContext == null) {
      throw new PartnerTransportException(PartnerTransportFailure.TLS_FAILURE);
    }

    HttpGet outbound = new HttpGet(uri);
    outbound.setHeader("Accept", "application/fhir+json");
    outbound.setHeader("Accept-Encoding", "identity");
    request
        .correlationId()
        .ifPresent(
            correlationId ->
                outbound.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId.value()));
    bearerToken.ifPresent(
        token -> outbound.setHeader("Authorization", token.authorizationHeader()));
    HttpClientContext context = HttpClientContext.create();
    Duration operationTimeout = Duration.ofMillis(configuration.limits().operationTimeoutMillis());

    PoolingHttpClientConnectionManager manager =
        buildConnectionManager(configuration, resolver, sslContext);
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
                .setDefaultRequestConfig(requestConfig(configuration))
                .build()) {
      return operations.execute(
          () -> {
            beforeAttempt.run();
            return executeOneRequest(
                client, outbound, context, resolver, activeProfile, configuration);
          },
          operationTimeout,
          () -> {
            outbound.cancel();
            try {
              client.close();
            } catch (IOException ignored) {
              // Cancellation already fails closed; transport diagnostics remain sanitized.
            }
          });
    } catch (PartnerTransportException exception) {
      throw exception;
    } catch (Exception exception) {
      throw classify(exception);
    }
  }

  @Override
  public PartnerTokenEndpointResponse requestToken(
      PartnerProfileVersion activeProfile, PartnerTokenEndpointRequest request) {
    return requestToken(activeProfile, request, () -> {});
  }

  @Override
  public PartnerTokenEndpointResponse requestToken(
      PartnerProfileVersion activeProfile,
      PartnerTokenEndpointRequest request,
      Runnable beforeAttempt) {
    Objects.requireNonNull(activeProfile, "activeProfile");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(beforeAttempt, "beforeAttempt");
    PartnerTrustConfiguration configuration = activeProfile.configuration();
    PartnerTrustConfiguration.Authentication authentication = configuration.authentication();
    if (activeProfile.state() != PartnerProfileState.ACTIVE) {
      throw new PartnerTransportException(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    }
    boolean clientSecretBasic = request.basicAuthorization() != null;
    boolean authenticationMatchesRequest =
        switch (authentication.method()) {
          case OAUTH_CLIENT_CREDENTIALS -> clientSecretBasic;
          case OAUTH_PRIVATE_KEY_JWT -> !clientSecretBasic;
          case NONE, MUTUAL_TLS -> false;
        };
    if (!authenticationMatchesRequest) {
      throw new PartnerTransportException(PartnerTransportFailure.AUTHENTICATION_NOT_SUPPORTED);
    }

    URI uri =
        URI.create(
            authentication.tokenOrigin().orElseThrow() + authentication.tokenPath().orElseThrow());
    int port = uri.getPort() < 0 ? 443 : uri.getPort();
    VettedDnsResolver resolver =
        new VettedDnsResolver(
            uri.getHost(),
            configuration.partnerKey(),
            port,
            Duration.ofMillis(configuration.limits().connectTimeoutMillis()),
            addressPolicy,
            addressLookup);
    SSLContext sslContext = tlsContexts.resolve(configuration);
    if (sslContext == null) {
      throw new PartnerTransportException(PartnerTransportFailure.TLS_FAILURE);
    }

    HttpPost outbound = new HttpPost(uri);
    outbound.setHeader("Accept", "application/json");
    outbound.setHeader("Accept-Encoding", "identity");
    if (request.basicAuthorization() != null) {
      outbound.setHeader("Authorization", request.basicAuthorization());
    }
    outbound.setEntity(
        new StringEntity(
            request.formBody(),
            ContentType.APPLICATION_FORM_URLENCODED.withCharset(
                java.nio.charset.StandardCharsets.UTF_8)));
    HttpClientContext context = HttpClientContext.create();
    Duration operationTimeout = Duration.ofMillis(configuration.limits().operationTimeoutMillis());
    PoolingHttpClientConnectionManager manager =
        buildConnectionManager(configuration, resolver, sslContext);
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
                .setDefaultRequestConfig(requestConfig(configuration))
                .build()) {
      return operations.execute(
          () -> {
            beforeAttempt.run();
            return client.execute(
                outbound,
                context,
                response -> handleTokenResponse(response, context, resolver, activeProfile));
          },
          operationTimeout,
          () -> {
            outbound.cancel();
            try {
              client.close();
            } catch (IOException ignored) {
              // Cancellation already fails closed; transport diagnostics remain sanitized.
            }
          });
    } catch (PartnerTransportException exception) {
      throw exception;
    } catch (Exception exception) {
      throw classify(exception);
    }
  }

  private static PartnerOutboundReadResponse executeOneRequest(
      CloseableHttpClient client,
      HttpGet request,
      HttpClientContext context,
      VettedDnsResolver resolver,
      PartnerProfileVersion profile,
      PartnerTrustConfiguration configuration) {
    try {
      return client.execute(
          request,
          context,
          response -> handleResponse(response, context, resolver, profile, configuration));
    } catch (PartnerTransportException exception) {
      throw exception;
    } catch (Exception exception) {
      throw classify(exception);
    }
  }

  private static PartnerOutboundReadResponse handleResponse(
      ClassicHttpResponse response,
      HttpClientContext context,
      VettedDnsResolver resolver,
      PartnerProfileVersion profile,
      PartnerTrustConfiguration configuration)
      throws IOException {
    verifyPeer(context, resolver);
    int status = response.getCode();
    if (status >= 300 && status < 400) {
      throw new PartnerTransportException(PartnerTransportFailure.INVALID_RESPONSE);
    }
    if (status >= 400 && status < 500) {
      if (status == 401) {
        throw new PartnerTransportException(PartnerTransportFailure.REMOTE_AUTHENTICATION_FAILURE);
      }
      if (status == 403) {
        throw new PartnerTransportException(PartnerTransportFailure.REMOTE_AUTHORIZATION_FAILURE);
      }
      if (status == 429) {
        throw new PartnerTransportException(
            PartnerTransportFailure.RATE_LIMITED,
            parseRetryAfter(responseHeader(response, "Retry-After")));
      }
      throw new PartnerTransportException(PartnerTransportFailure.REMOTE_4XX);
    }
    if (status >= 500) {
      throw new PartnerTransportException(PartnerTransportFailure.REMOTE_5XX);
    }
    if (status != 200) {
      throw new PartnerTransportException(PartnerTransportFailure.INVALID_RESPONSE);
    }

    String contentType = responseHeader(response, "Content-Type");
    if (contentType == null || !"application/fhir+json".equals(mediaType(contentType))) {
      throw new PartnerTransportException(PartnerTransportFailure.INVALID_RESPONSE);
    }
    HttpEntity entity = response.getEntity();
    if (entity == null) {
      throw new PartnerTransportException(PartnerTransportFailure.INVALID_RESPONSE);
    }
    long contentLength = entity.getContentLength();
    int compressedLimit = configuration.limits().maxResponseBytes();
    if (contentLength > compressedLimit) {
      throw new PartnerTransportException(PartnerTransportFailure.RESPONSE_TOO_LARGE);
    }
    byte[] compressed;
    try (InputStream input = entity.getContent()) {
      compressed = readBounded(input, compressedLimit);
    } catch (ResponseTooLargeException exception) {
      throw new PartnerTransportException(PartnerTransportFailure.RESPONSE_TOO_LARGE);
    }
    byte[] body = decompressBounded(response, compressed, configuration);
    return new PartnerOutboundReadResponse(
        status, Optional.of(mediaType(contentType)), body, profile.version());
  }

  private static PartnerOutboundOperationResponse handleOperationResponse(
      ClassicHttpResponse response,
      HttpClientContext context,
      VettedDnsResolver resolver,
      PartnerProfileVersion profile,
      PartnerTrustConfiguration configuration)
      throws IOException {
    verifyPeer(context, resolver);
    int status = response.getCode();
    if (status >= 300 && status < 400) {
      throw new PartnerTransportException(PartnerTransportFailure.INVALID_RESPONSE);
    }
    if (status >= 400 && status < 500) {
      if (status == 401) {
        throw new PartnerTransportException(PartnerTransportFailure.REMOTE_AUTHENTICATION_FAILURE);
      }
      if (status == 403) {
        throw new PartnerTransportException(PartnerTransportFailure.REMOTE_AUTHORIZATION_FAILURE);
      }
      if (status == 429) {
        throw new PartnerTransportException(
            PartnerTransportFailure.RATE_LIMITED,
            parseRetryAfter(responseHeader(response, "Retry-After")));
      }
      if (status == 404) {
        throw new PartnerTransportException(PartnerTransportFailure.REMOTE_NOT_FOUND);
      }
      throw new PartnerTransportException(PartnerTransportFailure.REMOTE_4XX);
    }
    if (status >= 500) {
      throw new PartnerTransportException(PartnerTransportFailure.REMOTE_5XX);
    }
    if (status < 200 || status >= 300) {
      throw new PartnerTransportException(PartnerTransportFailure.INVALID_RESPONSE);
    }

    HttpEntity entity = response.getEntity();
    if (entity == null) {
      return new PartnerOutboundOperationResponse(
          status, Optional.empty(), new byte[0], profile.version());
    }
    if (entity.getContentLength() > configuration.limits().maxResponseBytes()) {
      throw new PartnerTransportException(PartnerTransportFailure.RESPONSE_TOO_LARGE);
    }
    String contentEncoding = responseHeader(response, "Content-Encoding");
    if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding.strip())) {
      throw new PartnerTransportException(PartnerTransportFailure.UNSUPPORTED_CONTENT_ENCODING);
    }
    byte[] body;
    try (InputStream input = entity.getContent()) {
      body = readBounded(input, configuration.limits().maxResponseBytes());
    } catch (ResponseTooLargeException tooLarge) {
      throw new PartnerTransportException(PartnerTransportFailure.RESPONSE_TOO_LARGE);
    }
    if (body.length == 0) {
      return new PartnerOutboundOperationResponse(
          status, Optional.empty(), body, profile.version());
    }
    String contentType = responseHeader(response, "Content-Type");
    if (contentType == null || !"application/json".equals(mediaType(contentType))) {
      throw new PartnerTransportException(PartnerTransportFailure.INVALID_RESPONSE);
    }
    return new PartnerOutboundOperationResponse(
        status, Optional.of(mediaType(contentType)), body, profile.version());
  }

  private static PartnerTokenEndpointResponse handleTokenResponse(
      ClassicHttpResponse response,
      HttpClientContext context,
      VettedDnsResolver resolver,
      PartnerProfileVersion profile)
      throws IOException {
    verifyPeer(context, resolver);
    int status = response.getCode();
    if (status != 200) {
      return new PartnerTokenEndpointResponse(
          status, Optional.empty(), new byte[0], profile.version());
    }
    String contentType = responseHeader(response, "Content-Type");
    if (contentType == null || !"application/json".equals(mediaType(contentType))) {
      throw new PartnerTransportException(PartnerTransportFailure.INVALID_RESPONSE);
    }
    String contentEncoding = responseHeader(response, "Content-Encoding");
    if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding.strip())) {
      throw new PartnerTransportException(PartnerTransportFailure.UNSUPPORTED_CONTENT_ENCODING);
    }
    HttpEntity entity = response.getEntity();
    if (entity == null || entity.getContentLength() > 16_384) {
      throw new PartnerTransportException(PartnerTransportFailure.RESPONSE_TOO_LARGE);
    }
    try (InputStream input = entity.getContent()) {
      return new PartnerTokenEndpointResponse(
          status,
          Optional.of(mediaType(contentType)),
          readBounded(input, 16_384),
          profile.version());
    } catch (ResponseTooLargeException exception) {
      throw new PartnerTransportException(PartnerTransportFailure.RESPONSE_TOO_LARGE);
    }
  }

  private static byte[] decompressBounded(
      ClassicHttpResponse response, byte[] compressed, PartnerTrustConfiguration configuration) {
    Header[] headers = response.getHeaders("Content-Encoding");
    if (headers.length == 0) {
      if (compressed.length > configuration.limits().maxDecompressedBytes()) {
        throw new PartnerTransportException(PartnerTransportFailure.RESPONSE_TOO_LARGE);
      }
      return compressed;
    }
    if (headers.length != 1) {
      throw new PartnerTransportException(PartnerTransportFailure.UNSUPPORTED_CONTENT_ENCODING);
    }
    String encoding = headers[0].getValue().strip().toLowerCase(Locale.ROOT);
    if ("identity".equals(encoding)) {
      if (compressed.length > configuration.limits().maxDecompressedBytes()) {
        throw new PartnerTransportException(PartnerTransportFailure.RESPONSE_TOO_LARGE);
      }
      return compressed;
    }
    try (InputStream source = new ByteArrayInputStream(compressed);
        InputStream decoded =
            switch (encoding) {
              case "gzip" -> new GZIPInputStream(source);
              case "deflate" -> new InflaterInputStream(source);
              default ->
                  throw new PartnerTransportException(
                      PartnerTransportFailure.UNSUPPORTED_CONTENT_ENCODING);
            }) {
      return readBounded(decoded, configuration.limits().maxDecompressedBytes());
    } catch (ResponseTooLargeException exception) {
      throw new PartnerTransportException(PartnerTransportFailure.RESPONSE_TOO_LARGE);
    } catch (PartnerTransportException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new PartnerTransportException(PartnerTransportFailure.INVALID_RESPONSE);
    }
  }

  private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8_192));
    byte[] buffer = new byte[8_192];
    int total = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (read == 0) {
        continue;
      }
      if (read > maxBytes - total) {
        throw new ResponseTooLargeException();
      }
      output.write(buffer, 0, read);
      total += read;
    }
    return output.toByteArray();
  }

  private static void verifyPeer(HttpClientContext context, VettedDnsResolver resolver) {
    if (context.getEndpointDetails() == null) {
      throw new PartnerTransportException(PartnerTransportFailure.PEER_MISMATCH);
    }
    SocketAddress remote = context.getEndpointDetails().getRemoteAddress();
    if (!(remote instanceof InetSocketAddress socketAddress)
        || !resolver.isVettedPeer(socketAddress.getAddress())) {
      throw new PartnerTransportException(PartnerTransportFailure.PEER_MISMATCH);
    }
  }

  private static PoolingHttpClientConnectionManager buildConnectionManager(
      PartnerTrustConfiguration configuration, VettedDnsResolver resolver, SSLContext sslContext) {
    int connectTimeout = configuration.limits().connectTimeoutMillis();
    int responseTimeout = configuration.limits().responseTimeoutMillis();
    ConnectionConfig connectionConfig =
        ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
            .setSocketTimeout(Timeout.ofMilliseconds(responseTimeout))
            .build();
    return PoolingHttpClientConnectionManagerBuilder.create()
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
            SocketConfig.custom().setSoTimeout(Timeout.ofMilliseconds(responseTimeout)).build())
        .build();
  }

  private static RequestConfig requestConfig(PartnerTrustConfiguration configuration) {
    int connectTimeout = configuration.limits().connectTimeoutMillis();
    int responseTimeout = configuration.limits().responseTimeoutMillis();
    return RequestConfig.custom()
        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout))
        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
        .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout))
        .setRedirectsEnabled(false)
        .setAuthenticationEnabled(false)
        .setContentCompressionEnabled(false)
        .setHardCancellationEnabled(true)
        .build();
  }

  private static String responseHeader(ClassicHttpResponse response, String name) {
    Header header = response.getFirstHeader(name);
    return header == null ? null : header.getValue();
  }

  private static String mediaType(String contentType) {
    int semicolon = contentType.indexOf(';');
    String mediaType = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
    return mediaType.strip().toLowerCase(Locale.ROOT);
  }

  private static Optional<Duration> parseRetryAfter(String header) {
    if (header == null || !header.matches("[0-9]{1,6}")) {
      return Optional.empty();
    }
    try {
      return Optional.of(Duration.ofSeconds(Math.min(Long.parseLong(header), 30)));
    } catch (NumberFormatException invalid) {
      return Optional.empty();
    }
  }

  private static PartnerTransportException classify(Throwable failure) {
    for (Throwable candidate = failure; candidate != null; candidate = candidate.getCause()) {
      if (candidate instanceof PartnerTransportException transportException) {
        return transportException;
      }
      if (candidate instanceof DnsResolutionFailure dnsFailure) {
        return new PartnerTransportException(
            switch (dnsFailure.reason()) {
              case TIMED_OUT -> PartnerTransportFailure.DNS_TIMEOUT;
              case CAPACITY_EXCEEDED -> PartnerTransportFailure.DNS_CAPACITY_EXCEEDED;
              case DESTINATION_REJECTED -> PartnerTransportFailure.DESTINATION_REJECTED;
              case FAILED -> PartnerTransportFailure.DNS_FAILURE;
            });
      }
      if (candidate instanceof UnknownHostException) {
        return new PartnerTransportException(PartnerTransportFailure.DNS_FAILURE);
      }
      if (candidate instanceof SSLException) {
        return new PartnerTransportException(PartnerTransportFailure.TLS_FAILURE);
      }
      if (candidate instanceof ConnectTimeoutException
          || candidate instanceof ConnectionRequestTimeoutException) {
        return new PartnerTransportException(PartnerTransportFailure.CONNECT_TIMEOUT);
      }
      if (candidate instanceof SocketTimeoutException) {
        return new PartnerTransportException(PartnerTransportFailure.READ_TIMEOUT);
      }
    }
    return new PartnerTransportException(PartnerTransportFailure.CONNECTION_FAILURE);
  }

  private static final class ResponseTooLargeException extends IOException {}
}
