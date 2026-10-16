package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import java.util.Optional;

/** Bounded transient technical acknowledgement; business meaning remains with the owner. */
public record PartnerOutboundOperationResponse(
    int status, Optional<String> contentType, byte[] body, int configurationVersion) {

  public PartnerOutboundOperationResponse {
    if (status < 200 || status >= 300) {
      throw new IllegalArgumentException("successful partner response status is required");
    }
    contentType = Objects.requireNonNull(contentType, "contentType");
    body = Objects.requireNonNull(body, "body").clone();
    if (configurationVersion < 1) {
      throw new IllegalArgumentException("partner configuration version must be positive");
    }
    if (body.length == 0 && contentType.isPresent()) {
      throw new IllegalArgumentException("an empty response cannot declare a content type");
    }
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public String toString() {
    return "PartnerOutboundOperationResponse[status="
        + status
        + ", contentType="
        + contentType
        + ", body=redacted, configurationVersion="
        + configurationVersion
        + "]";
  }
}
