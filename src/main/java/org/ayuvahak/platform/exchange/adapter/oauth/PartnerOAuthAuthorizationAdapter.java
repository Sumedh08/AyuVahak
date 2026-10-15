package org.ayuvahak.platform.exchange.adapter.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.exchange.application.PartnerAuthorizationPort;
import org.ayuvahak.platform.exchange.application.PartnerBearerToken;
import org.ayuvahak.platform.exchange.application.PartnerSecretMaterial;
import org.ayuvahak.platform.exchange.application.PartnerSecretProvider;
import org.ayuvahak.platform.exchange.application.PartnerTokenEndpointPort;
import org.ayuvahak.platform.exchange.application.PartnerTokenEndpointRequest;
import org.ayuvahak.platform.exchange.application.PartnerTokenEndpointResponse;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Authentication;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AuthenticationMethod;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Partner-scoped OAuth client authentication and short-lived, configuration-versioned token cache.
 */
@Component
final class PartnerOAuthAuthorizationAdapter implements PartnerAuthorizationPort {

  private static final int MAX_CACHE_ENTRIES = 128;
  private static final int MAX_TOKEN_RESPONSE_BYTES = 16_384;
  private static final Duration MAX_TOKEN_LIFETIME = Duration.ofHours(1);
  private static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(2);
  private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
  private static final Duration CACHE_EXPIRY_SAFETY = Duration.ofSeconds(30);
  private static final int MINIMUM_RSA_BITS = 2_048;

  private final PartnerSecretProvider secrets;
  private final PartnerTokenEndpointPort tokenEndpoint;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final LinkedHashMap<CacheKey, PartnerBearerToken> cache =
      new LinkedHashMap<>(16, 0.75f, true);

  PartnerOAuthAuthorizationAdapter(
      PartnerSecretProvider secrets,
      PartnerTokenEndpointPort tokenEndpoint,
      ObjectMapper objectMapper,
      @Qualifier("auditClock") Clock clock) {
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public synchronized Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion profile) {
    return bearerToken(profile, () -> {});
  }

  @Override
  public synchronized Optional<PartnerBearerToken> bearerToken(
      PartnerProfileVersion profile, Runnable beforeAttempt) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(beforeAttempt, "beforeAttempt");
    PartnerTrustConfiguration configuration = profile.configuration();
    Authentication authentication = configuration.authentication();
    if (authentication.method() == AuthenticationMethod.NONE) {
      return Optional.empty();
    }
    if (authentication.method() == AuthenticationMethod.MUTUAL_TLS) {
      throw new PartnerTransportException(PartnerTransportFailure.AUTHENTICATION_NOT_SUPPORTED);
    }

    CacheKey key = new CacheKey(configuration.partnerKey(), profile.version());
    evictOtherVersions(configuration.partnerKey(), profile.version());
    Instant now = clock.instant();
    PartnerBearerToken cached = cache.get(key);
    if (cached != null && cached.expiresAt().isAfter(now.plus(CACHE_EXPIRY_SAFETY))) {
      return Optional.of(cached);
    }
    cache.remove(key);

    PartnerBearerToken acquired = acquire(profile, now, beforeAttempt);
    cache.put(key, acquired);
    trimCache();
    return Optional.of(acquired);
  }

  @Override
  public synchronized void invalidate(PartnerKey partnerKey) {
    Objects.requireNonNull(partnerKey, "partnerKey");
    cache.keySet().removeIf(key -> key.partnerKey().equals(partnerKey));
  }

  private PartnerBearerToken acquire(
      PartnerProfileVersion profile, Instant now, Runnable beforeAttempt) {
    PartnerTrustConfiguration configuration = profile.configuration();
    Authentication authentication = configuration.authentication();
    String clientId = authentication.clientId().orElseThrow();
    PartnerTokenEndpointRequest request =
        switch (authentication.method()) {
          case OAUTH_CLIENT_CREDENTIALS ->
              clientSecretRequest(authentication.credentialReference().orElseThrow(), clientId);
          case OAUTH_PRIVATE_KEY_JWT ->
              privateKeyJwtRequest(profile, authentication, clientId, now);
          default ->
              throw new PartnerTransportException(
                  PartnerTransportFailure.AUTHENTICATION_NOT_SUPPORTED);
        };
    PartnerTokenEndpointResponse response =
        tokenEndpoint.requestToken(profile, request, beforeAttempt);
    if (response.status() != 200
        || response.configurationVersion() != profile.version()
        || response.body().length > MAX_TOKEN_RESPONSE_BYTES) {
      throw authenticationFailure();
    }
    TokenResponse tokenResponse = parseTokenResponse(response.body());
    JWKSet trustedKeys = resolveJwkSet(authentication.tokenSigningKeysReference().orElseThrow());
    Instant tokenExpiry =
        validateAccessToken(tokenResponse.accessToken(), trustedKeys, authentication, now);
    Instant responseExpiry = now.plusSeconds(tokenResponse.expiresInSeconds());
    Instant expiry = tokenExpiry.isBefore(responseExpiry) ? tokenExpiry : responseExpiry;
    if (!expiry.isAfter(now.plus(CACHE_EXPIRY_SAFETY))) {
      throw authenticationFailure();
    }
    return new PartnerBearerToken(tokenResponse.accessToken(), expiry);
  }

  private PartnerTokenEndpointRequest clientSecretRequest(String reference, String clientId) {
    try (PartnerSecretMaterial material = secrets.resolve(reference).orElseThrow()) {
      char[] secret = material.copy();
      try {
        return PartnerTokenEndpointRequest.clientSecretBasic(clientId, secret);
      } finally {
        java.util.Arrays.fill(secret, '\0');
      }
    } catch (RuntimeException exception) {
      throw authenticationFailure();
    }
  }

  private PartnerTokenEndpointRequest privateKeyJwtRequest(
      PartnerProfileVersion profile, Authentication authentication, String clientId, Instant now) {
    String reference = authentication.credentialReference().orElseThrow();
    try (PartnerSecretMaterial material = secrets.resolve(reference).orElseThrow()) {
      char[] value = material.copy();
      String jwkJson;
      try {
        jwkJson = new String(value);
      } finally {
        java.util.Arrays.fill(value, '\0');
      }
      JWK jwk = JWK.parse(jwkJson);
      if (!(jwk instanceof RSAKey rsaKey)
          || !rsaKey.isPrivate()
          || rsaKey.size() < MINIMUM_RSA_BITS
          || (rsaKey.getKeyUse() != null && !KeyUse.SIGNATURE.equals(rsaKey.getKeyUse()))) {
        throw authenticationFailure();
      }
      String tokenEndpointAudience =
          authentication.tokenOrigin().orElseThrow() + authentication.tokenPath().orElseThrow();
      Instant assertionExpiry = now.plus(ASSERTION_LIFETIME);
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer(clientId)
              .subject(clientId)
              .audience(tokenEndpointAudience)
              .issueTime(Date.from(now))
              .expirationTime(Date.from(assertionExpiry))
              .jwtID(UUID.randomUUID().toString())
              .build();
      JWSHeader.Builder header =
          new JWSHeader.Builder(JWSAlgorithm.RS256).type(com.nimbusds.jose.JOSEObjectType.JWT);
      if (rsaKey.getKeyID() != null) {
        header.keyID(rsaKey.getKeyID());
      }
      SignedJWT assertion = new SignedJWT(header.build(), claims);
      assertion.sign(new RSASSASigner(rsaKey.toPrivateKey()));
      return PartnerTokenEndpointRequest.privateKeyJwt(clientId, assertion.serialize());
    } catch (PartnerTransportException exception) {
      throw exception;
    } catch (Exception exception) {
      throw authenticationFailure();
    }
  }

  private JWKSet resolveJwkSet(String reference) {
    try (PartnerSecretMaterial material = secrets.resolve(reference).orElseThrow()) {
      char[] value = material.copy();
      String json;
      try {
        json = new String(value);
      } finally {
        java.util.Arrays.fill(value, '\0');
      }
      JWKSet keySet = JWKSet.parse(json);
      if (keySet.getKeys().isEmpty() || keySet.getKeys().stream().anyMatch(JWK::isPrivate)) {
        throw authenticationFailure();
      }
      return keySet;
    } catch (PartnerTransportException exception) {
      throw exception;
    } catch (Exception exception) {
      throw authenticationFailure();
    }
  }

  private TokenResponse parseTokenResponse(byte[] body) {
    try {
      JsonNode document = objectMapper.readTree(body);
      if (document == null || !document.isObject()) {
        throw authenticationFailure();
      }
      JsonNode accessToken = document.get("access_token");
      JsonNode tokenType = document.get("token_type");
      JsonNode expiresIn = document.get("expires_in");
      if (accessToken == null
          || !accessToken.isTextual()
          || tokenType == null
          || !tokenType.isTextual()
          || !"bearer".equalsIgnoreCase(tokenType.stringValue())
          || expiresIn == null
          || !expiresIn.isIntegralNumber()
          || !expiresIn.canConvertToLong()) {
        throw authenticationFailure();
      }
      String value = accessToken.stringValue();
      long lifetime = expiresIn.longValue();
      if (value.isBlank()
          || value.length() > 8_192
          || value.codePoints().anyMatch(Character::isISOControl)
          || lifetime < 1
          || lifetime > MAX_TOKEN_LIFETIME.toSeconds()) {
        throw authenticationFailure();
      }
      return new TokenResponse(value, lifetime);
    } catch (PartnerTransportException exception) {
      throw exception;
    } catch (JacksonException exception) {
      throw authenticationFailure();
    }
  }

  private Instant validateAccessToken(
      String compact, JWKSet trustedKeys, Authentication authentication, Instant now) {
    try {
      SignedJWT token = SignedJWT.parse(compact);
      if (!JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm())
          || token.getHeader().getKeyID() == null) {
        throw authenticationFailure();
      }
      List<RSAKey> matchingKeys =
          trustedKeys.getKeys().stream()
              .filter(RSAKey.class::isInstance)
              .map(RSAKey.class::cast)
              .filter(key -> token.getHeader().getKeyID().equals(key.getKeyID()))
              .filter(key -> !key.isPrivate() && key.size() >= MINIMUM_RSA_BITS)
              .filter(key -> key.getKeyUse() == null || KeyUse.SIGNATURE.equals(key.getKeyUse()))
              .toList();
      if (matchingKeys.size() != 1
          || !token.verify(new RSASSAVerifier(matchingKeys.getFirst().toRSAPublicKey()))) {
        throw authenticationFailure();
      }
      JWTClaimsSet claims = token.getJWTClaimsSet();
      String issuer = authentication.issuer().orElseThrow();
      String audience = authentication.audience().orElseThrow();
      String clientId = authentication.clientId().orElseThrow();
      Date expiry = claims.getExpirationTime();
      Date notBefore = claims.getNotBeforeTime();
      Date issuedAt = claims.getIssueTime();
      List<String> audiences = claims.getAudience();
      String subject = claims.getSubject();
      Object clientClaim = claims.getClaim("client_id");
      if (!issuer.equals(claims.getIssuer())
          || audiences == null
          || !audiences.contains(audience)
          || expiry == null
          || !expiry.toInstant().isAfter(now)
          || (notBefore != null && notBefore.toInstant().isAfter(now.plus(CLOCK_SKEW)))
          || (issuedAt != null && issuedAt.toInstant().isAfter(now.plus(CLOCK_SKEW)))
          || (!clientId.equals(subject) && !clientId.equals(clientClaim))
          || (subject != null && clientClaim != null && !subject.equals(clientClaim))) {
        throw authenticationFailure();
      }
      return expiry.toInstant();
    } catch (PartnerTransportException exception) {
      throw exception;
    } catch (ParseException | JOSEException | RuntimeException exception) {
      throw authenticationFailure();
    }
  }

  private void evictOtherVersions(PartnerKey partnerKey, int currentVersion) {
    cache
        .keySet()
        .removeIf(
            key -> key.partnerKey().equals(partnerKey) && key.profileVersion() != currentVersion);
  }

  private void trimCache() {
    while (cache.size() > MAX_CACHE_ENTRIES) {
      CacheKey eldest = cache.keySet().iterator().next();
      cache.remove(eldest);
    }
  }

  private static PartnerTransportException authenticationFailure() {
    return new PartnerTransportException(PartnerTransportFailure.AUTHENTICATION_FAILURE);
  }

  private record CacheKey(PartnerKey partnerKey, int profileVersion) {}

  private record TokenResponse(String accessToken, long expiresInSeconds) {}
}
