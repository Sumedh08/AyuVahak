package org.ayuvahak.platform.exchange.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;

/** Owner-selected, relative read operation; it cannot carry a network destination. */
public record PartnerOutboundReadRequest(
    PartnerKey partnerKey,
    PartnerTrustConfiguration.AllowedOperation operation,
    List<String> pathSegments,
    Map<String, List<String>> queryParameters,
    Optional<String> continuationLink,
    Optional<CorrelationId> correlationId) {

  private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9._~$-]{1,128}");
  private static final Pattern QUERY_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]{0,63}");
  private static final int MAX_QUERY_VALUES = 100;
  private static final int MAX_QUERY_VALUE_LENGTH = 2_048;
  private static final int MAX_ENCODED_QUERY_LENGTH = 8_192;

  public PartnerOutboundReadRequest {
    Objects.requireNonNull(partnerKey, "partnerKey");
    Objects.requireNonNull(operation, "operation");
    continuationLink = Objects.requireNonNull(continuationLink, "continuationLink");
    correlationId = Objects.requireNonNull(correlationId, "correlationId");
    continuationLink.ifPresent(
        link -> {
          if (link.isBlank()
              || !link.equals(link.strip())
              || link.length() > 2_048
              || link.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("partner continuation link is invalid");
          }
        });
    if (operation.direction() != PartnerTrustConfiguration.Direction.OUTBOUND) {
      throw new IllegalArgumentException("outbound read requires an outbound owner operation");
    }
    pathSegments = List.copyOf(Objects.requireNonNull(pathSegments, "pathSegments"));
    if (pathSegments.size() > 8
        || pathSegments.stream()
            .anyMatch(
                segment ->
                    segment == null
                        || !PATH_SEGMENT.matcher(segment).matches()
                        || ".".equals(segment)
                        || "..".equals(segment))) {
      throw new IllegalArgumentException("relative path must contain bounded safe segments");
    }

    Map<String, List<String>> supplied = Objects.requireNonNull(queryParameters, "queryParameters");
    if (supplied.size() > 40) {
      throw new IllegalArgumentException("outbound query contains too many parameters");
    }
    TreeMap<String, List<String>> normalized = new TreeMap<>();
    int valueCount = 0;
    int totalLength = 0;
    for (Map.Entry<String, List<String>> entry : supplied.entrySet()) {
      String name = entry.getKey();
      List<String> values = entry.getValue();
      if (name == null
          || !QUERY_NAME.matcher(name).matches()
          || values == null
          || values.isEmpty()) {
        throw new IllegalArgumentException("outbound query parameter is invalid");
      }
      ArrayList<String> copied = new ArrayList<>();
      for (String value : values) {
        if (value == null
            || value.length() > MAX_QUERY_VALUE_LENGTH
            || value.codePoints().anyMatch(Character::isISOControl)) {
          throw new IllegalArgumentException("outbound query value is invalid");
        }
        copied.add(value);
        valueCount++;
        totalLength += name.length() + value.length() + 2;
      }
      normalized.put(name, List.copyOf(copied));
    }
    if (valueCount > MAX_QUERY_VALUES || totalLength > MAX_ENCODED_QUERY_LENGTH) {
      throw new IllegalArgumentException("outbound query exceeds configured bounds");
    }
    queryParameters = Map.copyOf(normalized);
  }

  public PartnerOutboundReadRequest(
      PartnerKey partnerKey,
      PartnerTrustConfiguration.AllowedOperation operation,
      List<String> pathSegments,
      Map<String, List<String>> queryParameters,
      Optional<String> continuationLink) {
    this(partnerKey, operation, pathSegments, queryParameters, continuationLink, Optional.empty());
  }

  public PartnerOutboundReadRequest(
      PartnerKey partnerKey,
      PartnerTrustConfiguration.AllowedOperation operation,
      List<String> pathSegments,
      Map<String, List<String>> queryParameters) {
    this(partnerKey, operation, pathSegments, queryParameters, Optional.empty(), Optional.empty());
  }

  public PartnerOutboundReadRequest(
      PartnerKey partnerKey,
      PartnerTrustConfiguration.AllowedOperation operation,
      List<String> pathSegments,
      Map<String, List<String>> queryParameters,
      CorrelationId correlationId) {
    this(
        partnerKey,
        operation,
        pathSegments,
        queryParameters,
        Optional.empty(),
        Optional.of(Objects.requireNonNull(correlationId, "correlationId")));
  }

  @Override
  public String toString() {
    return "PartnerOutboundReadRequest[partnerKey="
        + partnerKey
        + ", operation="
        + operation.operationCode()
        + ", pathSegments="
        + pathSegments.size()
        + ", queryParameters=redacted, continuation=redacted]";
  }
}
