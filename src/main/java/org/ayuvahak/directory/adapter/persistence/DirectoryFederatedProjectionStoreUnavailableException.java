package org.ayuvahak.directory.adapter.persistence;

/** Indicates that Directory could not safely read or reconcile its remote projection state. */
public final class DirectoryFederatedProjectionStoreUnavailableException extends RuntimeException {

  public DirectoryFederatedProjectionStoreUnavailableException(Throwable cause) {
    super("Directory federated projection storage is unavailable.", cause);
  }
}
