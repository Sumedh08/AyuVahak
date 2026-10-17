package org.ayuvahak.matching.domain;

/** Freshness state of a remote observation within the Matching deployment. */
public enum RemoteEvidenceFreshness {
  CURRENT,
  STALE,
  EXPIRED,
  UNKNOWN,
  CONFLICTED
}
