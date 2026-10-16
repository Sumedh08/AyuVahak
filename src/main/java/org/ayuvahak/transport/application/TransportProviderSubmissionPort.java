package org.ayuvahak.transport.application;

/** Owner-facing boundary to the configured Transport provider; it carries no arbitrary URL. */
public interface TransportProviderSubmissionPort {

  TransportProviderSubmissionResult submit(TransportProviderSubmissionRequest request);
}
