package org.ayuvahak.platform.notification.adapter.configuration;

import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.notification.domain.NotificationAuthorities;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers notification-specific scope permissions without changing Identity & Access. */
@Configuration(proxyBeanMethods = false)
public class NotificationAuthorityConfiguration {

  @Bean
  FunctionalAuthority notificationFeedReadAuthority() {
    return NotificationAuthorities.feedRead();
  }
}
