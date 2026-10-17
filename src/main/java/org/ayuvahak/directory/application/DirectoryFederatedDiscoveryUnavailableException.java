package org.ayuvahak.directory.application;

/**
 * A trusted remote Directory could not be read; stored facts remain subject to freshness expiry.
 */
public final class DirectoryFederatedDiscoveryUnavailableException extends RuntimeException {

  public DirectoryFederatedDiscoveryUnavailableException() {
    super("The configured remote Directory is unavailable.");
  }
}
