package org.ayuvahak.platform.exchange.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;

/** A bounded owner command to a configured operation; it never accepts a network destination. */
public record PartnerOutboundOperationRequest(
    PartnerKey partnerKey,
    AllowedOperation operation,
    List<String> pathSegments,
    byte[] body,
    String operationReference,
    Optional<CorrelationId> correlationId,
    Optional<Integer> expectedConfigurationVersion) {

  private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9._~$-]{1,128}");
  private static final Pattern OPERATION_REFERENCE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  public static final int MAX_REQUEST_BYTES = 1_048_576;

  public PartnerOutboundOperationRequest {
    Objects.requireNonNull(partnerKey, "partnerKey");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(correlationId, "correlationId");
    expectedConfigurationVersion =
        Objects.requireNonNull(expectedConfigurationVersion, "expected configuration version");
    if (expectedConfigurationVersion.filter(version -> version < 1).isPresent()) {
      throw new IllegalArgumentException("expected configuration version must be positive");
    }
    boolean transportOperation =
        operation.ownerContext() == OwnerContext.TRANSPORT
            && operation.direction() == Direction.OUTBOUND
            && operation.protocol() == Protocol.JSON;
    if (!transportOperation && !operation.isNamedAnalyticsDhis2DataValueSet()) {
      throw new IllegalArgumentException(
          "outbound operation commands require an allowlisted Transport or DHIS2 JSON operation");
    }
    pathSegments = List.copyOf(Objects.requireNonNull(pathSegments, "pathSegments"));
    if (pathSegments.isEmpty()
        || pathSegments.size() > 8
        || pathSegments.stream()
            .anyMatch(
                segment ->
                    segment == null
                        || !PATH_SEGMENT.matcher(segment).matches()
                        || ".".equals(segment)
                        || "..".equals(segment))) {
      throw new IllegalArgumentException(
          "relative operation path must contain bounded safe segments");
    }
    if (operation.isNamedAnalyticsDhis2DataValueSet()
        && !pathSegments.equals(List.of("dataValueSets"))) {
      throw new IllegalArgumentException(
          "Analytics DHIS2 export is limited to the configured dataValueSets operation path");
    }
    body = Objects.requireNonNull(body, "body").clone();
    if (body.length == 0 || body.length > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException(
          "outbound operation body is empty or exceeds its byte limit");
    }
    if (operationReference == null || !OPERATION_REFERENCE.matcher(operationReference).matches()) {
      throw new IllegalArgumentException("a bounded stable operation reference is required");
    }
  }

  public PartnerOutboundOperationRequest(
      PartnerKey partnerKey,
      AllowedOperation operation,
      List<String> pathSegments,
      byte[] body,
      String operationReference) {
    this(
        partnerKey,
        operation,
        pathSegments,
        body,
        operationReference,
        Optional.empty(),
        Optional.empty());
  }

  public PartnerOutboundOperationRequest(
      PartnerKey partnerKey,
      AllowedOperation operation,
      List<String> pathSegments,
      byte[] body,
      String operationReference,
      Optional<CorrelationId> correlationId) {
    this(
        partnerKey,
        operation,
        pathSegments,
        body,
        operationReference,
        correlationId,
        Optional.empty());
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public String toString() {
    return "PartnerOutboundOperationRequest[partnerKey="
        + partnerKey
        + ", operation="
        + operation.operationCode()
        + ", pathSegments="
        + pathSegments.size()
        + ", body=redacted, operationReference="
        + operationReference
        + "]";
  }
}
