package org.ayuvahak.platform.exchange.application;

/** Technical knowledge about a federated POST; it never claims the remote business result. */
public enum FederationPeerOperationDisposition {
  NOT_SENT,
  REMOTE_REJECTED,
  OUTCOME_UNKNOWN
}
