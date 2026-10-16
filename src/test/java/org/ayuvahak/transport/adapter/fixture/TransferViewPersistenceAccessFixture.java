package org.ayuvahak.transport.adapter.fixture;

import org.ayuvahak.transfer.application.TransferViewStore;

/** Test-only example proving Transport cannot access Transfer's private view persistence port. */
public final class TransferViewPersistenceAccessFixture {

  private final TransferViewStore transferViewStore;

  TransferViewPersistenceAccessFixture(TransferViewStore transferViewStore) {
    this.transferViewStore = transferViewStore;
  }

  TransferViewStore transferViewStore() {
    return transferViewStore;
  }
}
