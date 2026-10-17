package org.ayuvahak.platform.exchange.application;

/**
 * Sanitized technical causes exposed to the owner without endpoint, payload, or credential data.
 */
public enum FederationPeerOperationFailure {
  TRUST_DENIED,
  TRUST_UNAVAILABLE,
  AUTHENTICATION_FAILED,
  DESTINATION_NOT_REACHED,
  REMOTE_REJECTED,
  RESPONSE_UNCERTAIN
}
