package org.ayuvahak.directory.application;

/** Sanitized signal that a configured peer returned malformed or out-of-scope Directory facts. */
public final class DirectoryFederatedDiscoveryInvalidResponseException extends RuntimeException {

  public DirectoryFederatedDiscoveryInvalidResponseException() {
    super("Federated Directory response is invalid or outside the requested scope.");
  }
}
