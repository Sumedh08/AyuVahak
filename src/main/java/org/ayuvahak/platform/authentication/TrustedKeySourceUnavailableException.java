package org.ayuvahak.platform.authentication;

import org.springframework.security.oauth2.jwt.BadJwtException;

/** Indicates that verification could not be completed because a trusted key source was down. */
final class TrustedKeySourceUnavailableException extends BadJwtException {

  TrustedKeySourceUnavailableException(Throwable cause) {
    super("A trusted signing key could not be retrieved.", cause);
  }
}
