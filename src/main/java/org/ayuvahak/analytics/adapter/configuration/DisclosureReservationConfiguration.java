package org.ayuvahak.analytics.adapter.configuration;

import org.ayuvahak.analytics.application.NoSendEvidenceVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Safe default until the Phase 9 integration supplies immutable local no-send evidence. */
@Configuration(proxyBeanMethods = false)
class DisclosureReservationConfiguration {

  @Bean
  @ConditionalOnMissingBean(NoSendEvidenceVerifier.class)
  NoSendEvidenceVerifier failClosedNoSendEvidenceVerifier() {
    return (reservationId, outboundAttemptId) -> false;
  }
}
