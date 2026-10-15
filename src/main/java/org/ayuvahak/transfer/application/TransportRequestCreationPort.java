package org.ayuvahak.transfer.application;

import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.idempotency.RequestFingerprint;

/** Transport owner command boundary used after Transfer authorizes the human initiation. */
public interface TransportRequestCreationPort {

  Result create(
      ExecutionContext serviceContext,
      InternalId transferId,
      ActorReference initiatingActor,
      String idempotencyKey,
      RequestFingerprint fingerprint);

  record Result(
      InternalId transportRequestId,
      InternalId transferId,
      String status,
      String coordinationOutcome,
      boolean commandReplayed) {}
}
