package org.ayuvahak.platform.audit.application;

import java.time.Clock;
import java.util.Objects;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.core.InternalId;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** Records minimized evidence using the transaction boundary required by the audited action. */
@Service
@Lazy
public final class AuditWriter {

  private final AuditRecordAppender appender;
  private final Clock clock;

  public AuditWriter(AuditRecordAppender appender, Clock clock) {
    this.appender = Objects.requireNonNull(appender, "appender");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Appends required mutation evidence into the protected operation's already active transaction.
   */
  public InternalId recordMutation(AuditEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    if (evidence.actionKind() != AuditActionKind.PROTECTED_MUTATION) {
      throw new IllegalArgumentException("mutation capture requires protected mutation evidence");
    }
    InternalId id = InternalId.generate();
    appender.appendInOwnerTransaction(id, evidence, clock.instant());
    return id;
  }

  /** Commits non-mutating evidence independently before the caller proceeds to its outcome. */
  public InternalId recordNonMutating(AuditEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    if (evidence.actionKind() == AuditActionKind.PROTECTED_MUTATION) {
      throw new IllegalArgumentException(
          "non-mutating capture cannot persist protected mutation evidence");
    }
    InternalId id = InternalId.generate();
    appender.appendDurably(id, evidence, clock.instant());
    return id;
  }
}
