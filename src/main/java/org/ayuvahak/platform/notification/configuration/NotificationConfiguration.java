package org.ayuvahak.platform.notification.configuration;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProjectionProperties.class)
public class NotificationConfiguration {

  @Bean
  Clock notificationClock() {
    return Clock.systemUTC();
  }
}
