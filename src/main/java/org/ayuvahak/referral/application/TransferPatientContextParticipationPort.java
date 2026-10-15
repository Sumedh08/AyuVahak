package org.ayuvahak.referral.application;

import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Referral's narrow request to Transfer to verify the current selected receiving participant. */
public interface TransferPatientContextParticipationPort {

  Optional<ReferralTransferParticipation> verifyCurrentParticipant(
      ExecutionContext requester,
      InternalId referralId,
      InternalId transferId,
      InternalId requestAttemptId);
}
