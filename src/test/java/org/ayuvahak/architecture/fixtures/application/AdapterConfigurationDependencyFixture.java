package org.ayuvahak.architecture.fixtures.application;

/** Deliberately violates the application-to-adapter boundary for the architecture test. */
public final class AdapterConfigurationDependencyFixture {

  private final org.ayuvahak.directory.adapter.persistence.DirectoryStoreFixture adapter;

  public AdapterConfigurationDependencyFixture(
      org.ayuvahak.directory.adapter.persistence.DirectoryStoreFixture adapter) {
    this.adapter = adapter;
  }

  public Object adapter() {
    return adapter;
  }
}
