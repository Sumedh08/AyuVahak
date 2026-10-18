package org.ayuvahak.analytics.application;

import java.util.UUID;

/** Read-only Phase 9 evidence boundary for proving that no outbound request bytes were sent. */
@FunctionalInterface
public interface NoSendEvidenceVerifier {

  boolean provesNoRequestBytesCouldHaveLeft(UUID reservationId, UUID outboundAttemptId);
}
