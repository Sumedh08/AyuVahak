package org.ayuvahak.capacity.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityDimension;
import org.ayuvahak.capacity.domain.CapacityPolicy;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;

/** Capacity-owned persistence boundary for immutable dimension and policy versions. */
public interface CapacityPolicyStore extends CapacityConfigurationLookup {

  Optional<CapacityDimension> currentDimension(String code);

  Optional<CapacityDimension> lockCurrentDimension(String code);

  Optional<CapacityDimension> findDimensionVersion(InternalId revisionId);

  void insertDimensionVersion(CapacityDimension dimension);

  void appendDimensionVersion(CapacityDimension dimension, long expectedVersion);

  Optional<CapacityPolicy> currentPolicy(String dimensionCode);

  Optional<CapacityPolicy> lockCurrentPolicy(String dimensionCode);

  Optional<CapacityPolicy> findPolicyVersion(InternalId revisionId);

  void insertPolicyVersion(CapacityPolicy policy);

  void appendPolicyVersion(CapacityPolicy policy, long expectedVersion);

  ReceiptClaim claimReceipt(
      CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt);

  void configureBoundedReceiptWait();

  void completeReceipt(
      CommandReceiptKey key,
      int responseStatus,
      InternalId resultReference,
      long resultVersion,
      Instant recordedAt);

  enum ReceiptState {
    OWNER,
    REPLAY,
    KEY_REUSED,
    IN_PROGRESS
  }

  record ReceiptClaim(
      ReceiptState state,
      Optional<Integer> responseStatus,
      Optional<InternalId> resultReference,
      Optional<Long> resultVersion) {}
}
