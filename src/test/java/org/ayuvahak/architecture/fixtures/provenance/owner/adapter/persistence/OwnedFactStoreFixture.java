package org.ayuvahak.architecture.fixtures.provenance.owner.adapter.persistence;

import java.util.Optional;
import org.ayuvahak.architecture.fixtures.provenance.owner.domain.OwnedFactFixture;

/** In-memory test fixture only; no production persistence or schema is created. */
public final class OwnedFactStoreFixture {

  private OwnedFactFixture fact;

  public void save(OwnedFactFixture fact) {
    this.fact = fact;
  }

  public Optional<OwnedFactFixture> find() {
    return Optional.ofNullable(fact);
  }
}
