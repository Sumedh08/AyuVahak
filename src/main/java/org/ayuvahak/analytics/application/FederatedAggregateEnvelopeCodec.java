package org.ayuvahak.analytics.application;

import org.ayuvahak.analytics.domain.FederatedAggregateAcknowledgement;
import org.ayuvahak.analytics.domain.FederatedAggregateEnvelope;

/** Application-owned serialization boundary for the named Analytics federation contract. */
public interface FederatedAggregateEnvelopeCodec {

  byte[] encode(FederatedAggregateEnvelope envelope);

  FederatedAggregateAcknowledgement decodeAcknowledgement(byte[] body);
}
