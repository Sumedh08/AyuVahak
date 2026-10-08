package org.ayuvahak.platform.audit.application;

import java.time.Instant;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.core.InternalId;

/** Audit-owned persistence boundary used by the capture application service. */
public interface AuditRecordAppender {

  void appendInOwnerTransaction(InternalId id, AuditEvidence evidence, Instant recordedAt);

  void appendDurably(InternalId id, AuditEvidence evidence, Instant recordedAt);
}
