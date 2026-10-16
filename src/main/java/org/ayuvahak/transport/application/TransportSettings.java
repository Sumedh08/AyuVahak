package org.ayuvahak.transport.application;

/** Deployment values consumed by Transport policies, supplied by an outer configuration adapter. */
public interface TransportSettings {

  String getWorkerClientId();

  String getProviderPartnerKey();

  boolean hasProviderPartner();
}
