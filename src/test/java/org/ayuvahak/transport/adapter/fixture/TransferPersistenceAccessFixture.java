package org.ayuvahak.transport.adapter.fixture;

import org.ayuvahak.transfer.application.TransferStore;

/** Test-only example proving Transport cannot reach through Transfer's owner boundary. */
public final class TransferPersistenceAccessFixture {

  private final TransferStore transferStore;

  TransferPersistenceAccessFixture(TransferStore transferStore) {
    this.transferStore = transferStore;
  }

  TransferStore transferStore() {
    return transferStore;
  }
}
