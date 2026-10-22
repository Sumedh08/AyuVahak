package org.ayuvahak.platform.operatorsupport.application;

/** Safe status view that never contains an endpoint, secret reference, credential, or secret value. */
public record OperatorSupportProfileStatus(
    boolean enabled,
    String provider,
    boolean configurationValid,
    boolean secretReferenceConfigured) {

  public OperatorSupportProfileStatus {
    if (provider == null || !provider.matches("[A-Z_]{1,40}")) {
      throw new IllegalArgumentException("provider must be a bounded configuration identifier");
    }
  }
}
