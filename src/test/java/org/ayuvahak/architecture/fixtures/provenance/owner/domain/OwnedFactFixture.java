package org.ayuvahak.architecture.fixtures.provenance.owner.domain;

import org.ayuvahak.platform.core.Provenance;

/** Test-scoped example of a fact owner retaining its own minimized source evidence. */
public record OwnedFactFixture(String factReference, Provenance provenance) {}
