package org.ayuvahak.referral.application;

import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Referral-owned need for Transfer to confirm whether an active journey pins this revision. */
public interface TransferAmendmentStatusPort {

  boolean hasActiveCoordination(ExecutionContext referralRequest, InternalId referralId);
}
