package org.ayuvahak.directory.application;

/** Directory-owned semantic port for one bounded configured peer discovery request. */
public interface DirectoryFederatedDiscoveryPort {

  DirectoryFederatedDiscoveryPage discover(DirectoryFederatedDiscoveryQuery query);
}
