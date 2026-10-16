package org.ayuvahak.transport.adapter.transfer;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.transfer.application.TransferViews;
import org.ayuvahak.transport.application.TransferParticipantReadPort;
import org.springframework.stereotype.Component;

/** Delegates participant visibility to Transfer's authorized owner view. */
@Component
public final class TransferParticipantReadAdapter implements TransferParticipantReadPort {

  private final TransferViews views;

  public TransferParticipantReadAdapter(TransferViews views) {
    this.views = Objects.requireNonNull(views, "Transfer views");
  }

  @Override
  public void requireVisible(ExecutionContext context, InternalId transferId) {
    views.transfer(context, transferId);
  }
}
