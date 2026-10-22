package org.ayuvahak.transfer.adapter.configuration;

import org.ayuvahak.directory.application.DirectoryScopeReferenceLookup;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.transfer.application.TransferGrantTargetScopeValidator;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TransferProperties.class)
class TransferAuthorityConfiguration {

  @Bean
  TransferGrantTargetScopeValidator transferGrantTargetScopeValidator(
      DirectoryScopeReferenceLookup directoryScopeReferenceLookup) {
    return new TransferGrantTargetScopeValidator(directoryScopeReferenceLookup);
  }

  @Bean
  FunctionalAuthority transferRequestAuthority() {
    return TransferAuthorities.request();
  }

  @Bean
  FunctionalAuthority transferRequestExpiryAuthority() {
    return TransferAuthorities.requestExpiry();
  }

  @Bean
  FunctionalAuthority clinicalSuitabilityAuthority() {
    return TransferAuthorities.clinicalSuitability();
  }

  @Bean
  FunctionalAuthority operationalReadinessAuthority() {
    return TransferAuthorities.operationalReadiness();
  }

  @Bean
  FunctionalAuthority transferAcceptanceAuthority() {
    return TransferAuthorities.acceptance();
  }

  @Bean
  FunctionalAuthority transferReservationReconciliationAuthority() {
    return TransferAuthorities.reservationReconciliation();
  }

  @Bean
  FunctionalAuthority transferReferralCancellationAuthority() {
    return TransferAuthorities.referralCancellation();
  }

  @Bean
  FunctionalAuthority transferProgressAuthority() {
    return TransferAuthorities.progress();
  }

  @Bean
  FunctionalAuthority transferHandoverAuthority() {
    return TransferAuthorities.handover();
  }

  @Bean
  FunctionalAuthority transferCompletionAuthority() {
    return TransferAuthorities.completion();
  }

  @Bean
  FunctionalAuthority transferJourneyClosureAuthority() {
    return TransferAuthorities.closure();
  }

  @Bean
  FunctionalAuthority transferViewAuthority() {
    return TransferAuthorities.view();
  }

  @Bean
  FunctionalAuthority transferReportingSnapshotsAuthority() {
    return TransferAuthorities.reportingSnapshots();
  }

  @Bean
  FunctionalAuthority transferPatientContextParticipationAuthority() {
    return TransferAuthorities.patientContextParticipation();
  }

  @Bean
  FunctionalAuthority transferReferralAmendmentCheckAuthority() {
    return TransferAuthorities.referralAmendmentCheck();
  }

  @Bean
  FunctionalAuthority transferTransportRequestEligibilityAuthority() {
    return TransferAuthorities.transportRequestEligibility();
  }

  @Bean
  FunctionalAuthority transferRerouteAuthority() {
    return TransferAuthorities.reroute();
  }

  @Bean
  FunctionalAuthority transferTransportDestinationChangeEligibilityAuthority() {
    return TransferAuthorities.transportDestinationChangeEligibility();
  }
}
