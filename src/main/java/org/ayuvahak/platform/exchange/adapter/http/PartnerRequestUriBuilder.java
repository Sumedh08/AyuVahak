package org.ayuvahak.platform.exchange.adapter.http;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadRequest;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;

/** Builds a relative owner-selected FHIR read under the configured partner origin and base path. */
final class PartnerRequestUriBuilder {

  private PartnerRequestUriBuilder() {}

  static URI build(PartnerTrustConfiguration configuration, PartnerOutboundReadRequest request) {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(request, "request");
    if (!configuration.partnerKey().equals(request.partnerKey())
        || !configuration.allowedOperations().contains(request.operation())) {
      throw new IllegalArgumentException(
          "Requested operation is not allowed by this partner profile.");
    }
    StringBuilder value =
        new StringBuilder(configuration.endpointOrigin()).append(configuration.basePath());
    request.pathSegments().forEach(segment -> value.append('/').append(segment));
    String query = encodeQuery(queryParameters(configuration, request, value.toString()));
    if (!query.isEmpty()) {
      value.append('?').append(query);
    }
    try {
      URI uri = URI.create(value.toString());
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null) {
        throw new IllegalArgumentException(
            "Partner request URI is not a safe configured HTTPS URI.");
      }
      return uri;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Partner request URI is invalid.");
    }
  }

  private static Map<String, java.util.List<String>> queryParameters(
      PartnerTrustConfiguration configuration,
      PartnerOutboundReadRequest request,
      String configuredPath) {
    Map<String, java.util.List<String>> result = new TreeMap<>(request.queryParameters());
    request
        .continuationLink()
        .ifPresent(
            link -> {
              URI configured = URI.create(configuredPath);
              URI candidate;
              try {
                candidate = configured.resolve(URI.create(link));
              } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Partner continuation is invalid.");
              }
              int configuredPort = effectivePort(configuration.endpointOrigin());
              if (!"https".equalsIgnoreCase(candidate.getScheme())
                  || candidate.getHost() == null
                  || !candidate.getHost().equalsIgnoreCase(configured.getHost())
                  || effectivePort(candidate) != configuredPort
                  || candidate.getRawUserInfo() != null
                  || candidate.getRawFragment() != null
                  || !configured.getRawPath().equals(candidate.getRawPath())) {
                throw new IllegalArgumentException(
                    "Partner continuation must remain at the configured operation path.");
              }
              Map<String, String> pageParameters = parseContinuationQuery(candidate.getRawQuery());
              result.put("_getpages", java.util.List.of(pageParameters.get("_getpages")));
              if (pageParameters.containsKey("_getpagesoffset")) {
                result.put(
                    "_getpagesoffset", java.util.List.of(pageParameters.get("_getpagesoffset")));
              }
            });
    return result;
  }

  private static Map<String, String> parseContinuationQuery(String query) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("Partner continuation has no page token.");
    }
    Set<String> allowed = Set.of("_getpages", "_getpagesoffset", "_type", "_count", "_since");
    Map<String, String> result = new TreeMap<>();
    for (String pair : query.split("&", -1)) {
      String[] parts = pair.split("=", 2);
      if (parts.length != 2) {
        throw new IllegalArgumentException("Partner continuation query is invalid.");
      }
      String name = decode(parts[0]);
      String item = decode(parts[1]);
      if (!allowed.contains(name) || result.putIfAbsent(name, item) != null) {
        throw new IllegalArgumentException("Partner continuation query is not supported.");
      }
    }
    String page = result.get("_getpages");
    String offset = result.get("_getpagesoffset");
    if (page == null
        || page.isBlank()
        || page.length() > 2_048
        || page.codePoints().anyMatch(Character::isISOControl)
        || (offset != null && !offset.matches("[0-9]{1,9}"))) {
      throw new IllegalArgumentException("Partner continuation page token is invalid.");
    }
    return result;
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException invalidEncoding) {
      throw new IllegalArgumentException("Partner continuation query encoding is invalid.");
    }
  }

  private static int effectivePort(String origin) {
    URI uri = URI.create(origin);
    return effectivePort(uri);
  }

  private static int effectivePort(URI uri) {
    return uri.getPort() < 0 ? 443 : uri.getPort();
  }

  private static String encodeQuery(Map<String, java.util.List<String>> queryParameters) {
    StringBuilder query = new StringBuilder();
    queryParameters.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                entry
                    .getValue()
                    .forEach(
                        item -> {
                          if (!query.isEmpty()) {
                            query.append('&');
                          }
                          query.append(encode(entry.getKey())).append('=').append(encode(item));
                        }));
    return query.toString();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
