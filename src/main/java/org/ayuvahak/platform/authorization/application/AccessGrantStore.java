package org.ayuvahak.platform.authorization.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.platform.authorization.domain.AccessGrant;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;

/** Identity & Access-owned persistence boundary for grants and their command receipts. */
public interface AccessGrantStore {

  boolean anyGrantOrBootstrapMarkerExists();

  void lockForBootstrap();

  InternalId insertPlatformGrant(ActorReference principal, String authorityCode, Instant createdAt);

  long advanceEventSequence(InternalId grantId);

  void completeBootstrap(
      InternalId grantId,
      String operatorIssuer,
      String operatorReference,
      String hostPrincipal,
      String databasePrincipal,
      Instant completedAt);

  String currentDatabasePrincipal();

  ReceiptClaim claimReceipt(String scopeDigest, String keyDigest, String fingerprint, Instant now);

  void configureBoundedReceiptWait();

  void completeReceipt(
      String scopeDigest, String keyDigest, int status, InternalId resultReference, Instant now);

  List<AccessGrant> listPlatformGrants(Optional<UUID> afterId, int limit);

  Optional<AccessGrant> findGrant(InternalId id);

  OptionalLong revoke(InternalId id, Instant revokedAt);

  boolean databaseContainsGrant(ActorReference principal, String authorityCode);

  enum ReceiptState {
    OWNER,
    REPLAY,
    KEY_REUSED,
    IN_PROGRESS
  }

  record ReceiptClaim(
      ReceiptState state, Optional<Integer> responseStatus, Optional<InternalId> result) {}
}
