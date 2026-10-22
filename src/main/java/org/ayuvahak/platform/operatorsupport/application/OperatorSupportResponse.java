package org.ayuvahak.platform.operatorsupport.application;

import java.util.List;
import java.util.Objects;

/** Strict, bounded advisory or abstention payload with citations from the approved corpus only. */
public record OperatorSupportResponse(
    Status status,
    String answer,
    List<SourceCitation> sources,
    String capabilityVersion,
    String corpusVersion,
    ReasonCode reasonCode) {

  public enum Status {
    ADVISORY,
    ABSTAINED
  }

  public enum ReasonCode {
    NONE,
    DISABLED,
    NO_APPROVED_EVIDENCE,
    INVALID_PROVIDER_OUTPUT,
    PROVIDER_UNAVAILABLE,
    RE_EVALUATION_REQUIRED
  }

  public OperatorSupportResponse {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(answer, "answer");
    sources = List.copyOf(sources);
    requireVersion(capabilityVersion, "capability version");
    requireVersion(corpusVersion, "corpus version");
    Objects.requireNonNull(reasonCode, "reason code");
    if (status == Status.ADVISORY) {
      if (answer.isBlank()
          || answer.length() > 1400
          || sources.isEmpty()
          || reasonCode != ReasonCode.NONE) {
        throw new IllegalArgumentException("advisory output must be bounded and cited");
      }
    } else if (!answer.isEmpty() || !sources.isEmpty() || reasonCode == ReasonCode.NONE) {
      throw new IllegalArgumentException("abstentions must contain only a bounded reason code");
    }
  }

  private static void requireVersion(String value, String name) {
    if (value == null || !value.matches("[a-z0-9._-]{1,80}")) {
      throw new IllegalArgumentException(name + " must be a stable bounded identifier");
    }
  }

  @Override
  public String toString() {
    return "OperatorSupportResponse[status="
        + status
        + ", answer=redacted, sourceCount="
        + sources.size()
        + ", capabilityVersion="
        + capabilityVersion
        + ", corpusVersion="
        + corpusVersion
        + ", reasonCode="
        + reasonCode
        + "]";
  }

  public record SourceCitation(String sourceId, String path, String section, String contentHash) {
    public SourceCitation {
      Objects.requireNonNull(sourceId, "source id");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(section, "section");
      Objects.requireNonNull(contentHash, "content hash");
    }

    static SourceCitation from(ApprovedOperatorSupportSource source) {
      return new SourceCitation(
          source.sourceId(), source.path(), source.section(), source.contentHash());
    }
  }
}
