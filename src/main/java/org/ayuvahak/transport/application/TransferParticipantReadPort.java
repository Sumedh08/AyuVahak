package org.ayuvahak.transport.application;

import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Transfer-owned participant check required before a linked Transport view is disclosed. */
public interface TransferParticipantReadPort {

  void requireVisible(ExecutionContext context, InternalId transferId);
}
