package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import java.util.Optional;

/** Bounded transient response from a configured partner; diagnostics never include its body. */
public record PartnerOutboundReadResponse(
    int status, Optional<String> contentType, byte[] body, int configurationVersion) {

  public PartnerOutboundReadResponse {
    if (status < 200 || status >= 300) {
      throw new IllegalArgumentException("successful partner response status is required");
    }
    contentType = Objects.requireNonNull(contentType, "contentType");
    body = Objects.requireNonNull(body, "body").clone();
    if (configurationVersion < 1) {
      throw new IllegalArgumentException("partner configuration version must be positive");
    }
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public String toString() {
    return "PartnerOutboundReadResponse[status="
        + status
        + ", contentType="
        + contentType
        + ", body=redacted, configurationVersion="
        + configurationVersion
        + "]";
  }
}
