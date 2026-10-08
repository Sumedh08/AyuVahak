package org.ayuvahak.platform.authentication;

/** Signals that a verified token does not match the configured AyuVahak principal profile. */
public final class InvalidAccessTokenException extends RuntimeException {

  public InvalidAccessTokenException() {
    super("The access token does not match the configured principal profile.");
  }
}
