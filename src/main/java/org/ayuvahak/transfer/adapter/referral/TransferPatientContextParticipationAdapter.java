package org.ayuvahak.transfer.adapter.referral;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.referral.application.ReferralTransferParticipation;
import org.ayuvahak.referral.application.TransferPatientContextParticipationPort;
import org.ayuvahak.referral.application.TransferPatientContextParticipationUnavailableException;
import org.ayuvahak.transfer.application.PatientContextParticipation;
import org.ayuvahak.transfer.application.TransferPatientContextEligibilityQuery;
import org.springframework.stereotype.Component;

/** Transfer-owned adapter implementing Referral's eligibility-only participant port. */
@Component
public final class TransferPatientContextParticipationAdapter
    implements TransferPatientContextParticipationPort {

  private final TransferPatientContextEligibilityQuery transfer;

  public TransferPatientContextParticipationAdapter(
      TransferPatientContextEligibilityQuery transfer) {
    this.transfer = Objects.requireNonNull(transfer, "Transfer participant owner query");
  }

  @Override
  public Optional<ReferralTransferParticipation> verifyCurrentParticipant(
      ExecutionContext requester,
      InternalId referralId,
      InternalId transferId,
      InternalId requestAttemptId) {
    try {
      return transfer
          .verify(requester, referralId, transferId, requestAttemptId)
          .map(TransferPatientContextParticipationAdapter::map);
    } catch (RuntimeException unavailable) {
      throw new TransferPatientContextParticipationUnavailableException(unavailable);
    }
  }

  private static ReferralTransferParticipation map(PatientContextParticipation participation) {
    return new ReferralTransferParticipation(
        participation.referralId(),
        participation.transferId(),
        participation.requestAttemptId(),
        participation.referralRevision(),
        participation.receivingFacility());
  }
}
