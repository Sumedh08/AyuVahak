package org.ayuvahak.platform.exchange.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;

/**
 * Platform Exchange-owned persistence boundary for trust profiles and its local command receipts.
 */
public interface PartnerTrustProfileStore {

  void configureBoundedReceiptWait();

  PartnerProfileVersion createInitial(PartnerTrustConfiguration configuration, Instant now);

  PartnerProfileVersion createNextVersion(
      PartnerTrustConfiguration configuration, int expectedCurrentVersion, Instant now);

  PartnerProfileVersion activate(PartnerKey partnerKey, int version, Instant now);

  PartnerProfileVersion suspend(PartnerKey partnerKey, Instant now);

  PartnerProfileVersion revoke(PartnerKey partnerKey, Instant now);

  List<PartnerProfileVersion> versions(PartnerKey partnerKey);

  Optional<PartnerProfileVersion> findById(InternalId versionId);

  Optional<PartnerProfileVersion> findActive(
      PartnerKey partnerKey, PartnerTrustConfiguration.AllowedOperation operation);

  Optional<PartnerProfileVersion> findActiveInbound(
      ActorReference principal, PartnerTrustConfiguration.AllowedOperation operation);

  ReceiptClaim claimReceipt(CommandReceiptKey key, RequestFingerprint fingerprint, Instant now);

  void completeReceipt(
      CommandReceiptKey key, int responseStatus, InternalId resultVersion, Instant now);

  enum ReceiptState {
    OWNER,
    REPLAY,
    KEY_REUSED,
    IN_PROGRESS
  }

  record ReceiptClaim(
      ReceiptState state, Optional<Integer> responseStatus, Optional<InternalId> resultVersion) {}
}
