package org.ayuvahak.transfer.application;

/** Named owner exchange for an existing origin operation or its status recovery. */
@FunctionalInterface
public interface FederatedReceivingOriginExchange {

  FederatedReceivingOriginExchangeResult execute(
      org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt attempt, boolean statusOnly);
}
