package org.ayuvahak.platform.audit.adapter;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuditClockConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "auditClock")
  Clock auditClock() {
    return Clock.systemUTC();
  }
}
