package org.ayuvahak.referral.application;

import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Referral-owned need for Transfer to confirm whether an active journey pins this revision. */
public interface TransferAmendmentStatusPort {

  CoordinationGuard acquireCoordinationGuard(
      ExecutionContext referralRequest, InternalId referralId);

  boolean hasActiveCoordination(ExecutionContext referralRequest, InternalId referralId);

  @FunctionalInterface
  interface CoordinationGuard extends AutoCloseable {
    @Override
    void close();
  }
}
