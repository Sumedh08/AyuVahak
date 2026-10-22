package org.ayuvahak.testsupport.authentication;

import java.time.Clock;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.authentication.AuthenticationProperties;
import org.ayuvahak.platform.core.InternalId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test-only authentication audit boundary, outside the production component-scan root. */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthenticationProperties.class)
public class AuthenticationTestConfiguration {

  @Bean
  @ConditionalOnMissingBean(BuildProperties.class)
  BuildProperties buildProperties() {
    Properties properties = new Properties();
    properties.setProperty("version", "0.1.0");
    return new BuildProperties(properties);
  }

  @Bean
  TestAuditRecordAppender testAuditRecordAppender() {
    return new TestAuditRecordAppender();
  }

  @Bean
  AuditWriter testAuditWriter(TestAuditRecordAppender appender) {
    return new AuditWriter(appender, Clock.systemUTC());
  }

  public static final class TestAuditRecordAppender implements AuditRecordAppender {
    private final AtomicReference<AuditEvidence> latest = new AtomicReference<>();
    private final AtomicBoolean unavailable = new AtomicBoolean();

    @Override
    public void appendInOwnerTransaction(
        InternalId id, AuditEvidence evidence, Instant recordedAt) {
      latest.set(evidence);
    }

    @Override
    public void appendDurably(InternalId id, AuditEvidence evidence, Instant recordedAt) {
      if (unavailable.get()) {
        throw new AuditPersistenceUnavailableException(new IllegalStateException("synthetic"));
      }
      latest.set(evidence);
    }

    public void failWrites() {
      unavailable.set(true);
    }

    public void reset() {
      latest.set(null);
      unavailable.set(false);
    }

    public AuditEvidence lastEvidence() {
      return latest.get();
    }
  }
}
