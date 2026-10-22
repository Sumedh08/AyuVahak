package org.ayuvahak.platform.operatorsupport.application;

import java.net.URI;
import java.time.Duration;

/** Narrow JSON-only transport port for one configured, vetted operator-support provider profile. */
@FunctionalInterface
public interface OperatorSupportHttpTransport {

  byte[] postJson(
      URI configuredEndpoint,
      byte[] requestBody,
      char[] bearerCredential,
      Duration timeout,
      int maximumResponseBytes);
}
