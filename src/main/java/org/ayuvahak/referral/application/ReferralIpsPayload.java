package org.ayuvahak.referral.application;

import java.util.Arrays;
import java.util.Objects;

/** Raw IPS bytes held transiently within Referral while the bounded projection is produced. */
public record ReferralIpsPayload(
    byte[] content, String contentType, PatientContextSourceEvidence evidence) {

  public ReferralIpsPayload {
    content = Arrays.copyOf(Objects.requireNonNull(content, "IPS payload"), content.length);
    if (content.length == 0) {
      throw new IllegalArgumentException("IPS payload must not be empty");
    }
    if (contentType == null || contentType.isBlank() || contentType.length() > 128) {
      throw new IllegalArgumentException("IPS content type is invalid");
    }
    Objects.requireNonNull(evidence, "source evidence");
  }

  @Override
  public byte[] content() {
    return Arrays.copyOf(content, content.length);
  }

  @Override
  public String toString() {
    return "ReferralIpsPayload[content=redacted, contentType="
        + contentType
        + ", evidence="
        + evidence
        + "]";
  }
}
