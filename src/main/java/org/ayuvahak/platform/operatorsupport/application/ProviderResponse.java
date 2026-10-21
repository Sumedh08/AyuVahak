package org.ayuvahak.platform.operatorsupport.application;

import java.util.List;

/** Untrusted output of the narrow provider port; every field is validated before presentation. */
public record ProviderResponse(
    OperatorSupportResponse.Status status,
    String answer,
    List<String> sourceIds,
    OperatorSupportResponse.ReasonCode reasonCode) {

  public ProviderResponse {
    sourceIds = sourceIds == null ? null : List.copyOf(sourceIds);
  }

  public static ProviderResponse advisory(String answer, List<String> sourceIds) {
    return new ProviderResponse(
        OperatorSupportResponse.Status.ADVISORY,
        answer,
        sourceIds,
        OperatorSupportResponse.ReasonCode.NONE);
  }

  public static ProviderResponse abstained(OperatorSupportResponse.ReasonCode reasonCode) {
    return new ProviderResponse(
        OperatorSupportResponse.Status.ABSTAINED, "", List.of(), reasonCode);
  }

  @Override
  public String toString() {
    return "ProviderResponse[status="
        + status
        + ", answer=redacted, sourceCount="
        + (sourceIds == null ? "unknown" : sourceIds.size())
        + ", reasonCode="
        + reasonCode
        + "]";
  }
}
