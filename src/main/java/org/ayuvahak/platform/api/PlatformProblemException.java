package org.ayuvahak.platform.api;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/** A safe, stable problem response for an expected platform operation outcome. */
public final class PlatformProblemException extends RuntimeException {

  private final HttpStatus status;
  private final PlatformErrorCode code;

  public PlatformProblemException(HttpStatus status, PlatformErrorCode code, String message) {
    super(message);
    this.status = Objects.requireNonNull(status, "status");
    this.code = Objects.requireNonNull(code, "code");
  }

  public HttpStatus status() {
    return status;
  }

  public PlatformErrorCode code() {
    return code;
  }
}
