package org.ayuvahak.architecture.fixtures.provenance.consumer.application;

import org.ayuvahak.architecture.fixtures.provenance.owner.adapter.persistence.OwnedFactStoreFixture;

/** Deliberately invalid test fixture proving the cross-context persistence rule detects access. */
public final class UnrelatedModuleAccessFixture {

  private final OwnedFactStoreFixture store;

  public UnrelatedModuleAccessFixture(OwnedFactStoreFixture store) {
    this.store = store;
  }

  public OwnedFactStoreFixture store() {
    return store;
  }
}
