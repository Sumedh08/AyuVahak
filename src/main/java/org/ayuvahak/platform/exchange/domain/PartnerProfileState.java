package org.ayuvahak.platform.exchange.domain;

/** Lifecycle of one immutable partner trust configuration version. */
public enum PartnerProfileState {
  DRAFT,
  ACTIVE,
  SUSPENDED,
  REVOKED,
  SUPERSEDED
}
