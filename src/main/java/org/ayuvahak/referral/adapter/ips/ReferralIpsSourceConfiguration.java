package org.ayuvahak.referral.adapter.ips;

import org.ayuvahak.platform.exchange.application.PartnerOutboundReadPort;
import org.ayuvahak.platform.fhir.FhirPayloadLimits;
import org.ayuvahak.platform.fhir.FhirR4PayloadParser;
import org.ayuvahak.referral.application.ReferralIpsPayloadReadPort;
import org.ayuvahak.referral.application.ReferralPatientContextSourcePort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime wiring for Referral-owned, transient IPS context retrieval. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReferralIpsSourceProperties.class)
public class ReferralIpsSourceConfiguration {

  @Bean
  ReferralIpsPartnerBindings referralIpsPartnerBindings(ReferralIpsSourceProperties properties) {
    return new ReferralIpsPartnerBindings(properties.getSourceBindings());
  }

  @Bean("referralFhirR4PayloadParser")
  FhirR4PayloadParser referralFhirR4PayloadParser() {
    return FhirR4PayloadParser.withCoreProfiles(FhirPayloadLimits.conservativeDefaults());
  }

  @Bean
  ReferralIpsPatientContextMapper referralIpsPatientContextMapper(
      @Qualifier("referralFhirR4PayloadParser") FhirR4PayloadParser parser) {
    return new ReferralIpsPatientContextMapper(parser);
  }

  @Bean
  ReferralIpsPayloadReadPort referralIpsPayloadReadPort(
      ReferralIpsPartnerBindings bindings,
      PartnerOutboundReadPort outbound,
      @Qualifier("auditClock") java.time.Clock clock) {
    return new PartnerReferralIpsPayloadReadAdapter(bindings, outbound, clock);
  }

  @Bean
  ReferralPatientContextSourcePort referralPatientContextSourcePort(
      ReferralIpsPatientContextMapper mapper, ReferralIpsPayloadReadPort payloadReader) {
    return new ReferralIpsPatientContextSourceAdapter(mapper, payloadReader);
  }
}
