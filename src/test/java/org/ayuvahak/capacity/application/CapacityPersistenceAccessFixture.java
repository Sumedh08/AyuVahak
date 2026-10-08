package org.ayuvahak.capacity.application;

import org.ayuvahak.directory.adapter.persistence.DirectoryStoreFixture;

/** Deliberately invalid test-only dependency used to prove the context boundary rule. */
public final class CapacityPersistenceAccessFixture {

  private final DirectoryStoreFixture directoryStore = new DirectoryStoreFixture();

  public String readForTest() {
    return directoryStore.readForTest();
  }
}
