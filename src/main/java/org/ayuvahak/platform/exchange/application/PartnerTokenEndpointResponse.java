package org.ayuvahak.platform.exchange.application;

import java.util.Objects;
import java.util.Optional;

/** Bounded transient token-endpoint response; its body is always sensitive and redacted. */
public record PartnerTokenEndpointResponse(
    int status, Optional<String> contentType, byte[] body, int configurationVersion) {

  public PartnerTokenEndpointResponse {
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("HTTP status is outside its valid range");
    }
    contentType = Objects.requireNonNull(contentType, "contentType");
    body = Objects.requireNonNull(body, "body").clone();
    if (body.length > 16_384 || configurationVersion < 1) {
      throw new IllegalArgumentException("token endpoint response exceeds its bound");
    }
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public String toString() {
    return "PartnerTokenEndpointResponse[status="
        + status
        + ", contentType="
        + contentType
        + ", body=redacted, configurationVersion="
        + configurationVersion
        + "]";
  }
}
