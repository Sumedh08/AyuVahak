package org.ayuvahak.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.analytics.domain.AggregateInput;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.SourceMeasure;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class AnalyticsIndicatorDefinitionAdministrationServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(
          new IssuerId("urn:ayuvahak:directory"),
          "Organization",
          "10000000-0000-4000-8000-000000000001");
  private static final ActorReference ADMIN =
      ActorReference.human(
          new IssuerId("https://identity.example.test/realm"), "SYN-ANALYTICS-ADMIN");

  @Test
  void publishesOnlyForAuthorizedPlatformAdministratorAndAuditsTheVersion() {
    Harness harness = new Harness(true);
    IndicatorDefinitionVersion definition = definition();

    IndicatorDefinitionVersion result = harness.service().publish(context(), definition);

    assertThat(result).isEqualTo(definition);
    assertThat(harness.store.published).containsExactly(definition);
    assertThat(harness.audit.records).singleElement().satisfies(evidence -> {
      assertThat(evidence.actionCode()).isEqualTo("ANALYTICS_INDICATOR_DEFINITION_PUBLISHED");
      assertThat(evidence.targetType()).isEqualTo("IndicatorDefinitionVersion");
      assertThat(evidence.outcome().name()).isEqualTo("SUCCEEDED");
    });
  }

  @Test
  void deniedPublisherCannotReachTheAnalyticsStore() {
    Harness harness = new Harness(false);

    assertThatThrownBy(() -> harness.service().publish(context(), definition()))
        .isInstanceOf(PlatformProblemException.class);

    assertThat(harness.store.published).isEmpty();
    assertThat(harness.audit.records).singleElement().satisfies(evidence ->
        assertThat(evidence.outcome().name()).isEqualTo("DENIED"));
  }

  private static ExecutionContext context() {
    return new ExecutionContextFactory((actor, scope) -> true)
        .forInteractiveRequest(ADMIN, RequestedExecutionScope.platform(), CorrelationId.generate());
  }

  private static IndicatorDefinitionVersion definition() {
    return new IndicatorDefinitionVersion(
        "SYN.REFERRAL.SUBMITTED",
        1,
        "Synthetic submitted Referral count",
        org.ayuvahak.analytics.domain.AggregationFormula.SUM,
        java.time.ZoneId.of("UTC"),
        List.of(SourceMeasure.referral("SUBMITTED_COUNT")),
        true,
        List.of(),
        IndicatorDefinitionGovernance.syntheticFixture(
            "Demonstration only; not a public-health standard."));
  }

  private static final class Harness {
    private final RecordingStore store = new RecordingStore();
    private final RecordingAuditAppender audit = new RecordingAuditAppender();
    private final boolean allowed;

    private Harness(boolean allowed) {
      this.allowed = allowed;
    }

    private AnalyticsIndicatorDefinitionAdministrationService service() {
      AuthorizationGrantLookup grants = (actor, authority, scope) -> allowed;
      var authorization =
          new AuthorizationService(
              grants,
              new FunctionalAuthorityCatalog(
                  List.of(AnalyticsAuthorities.indicatorDefinitionAdministration())));
      PlatformTransactionManager transactionManager =
          new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
              return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
          };
      return new AnalyticsIndicatorDefinitionAdministrationService(
          store,
          authorization,
          new AuditWriter(audit, Clock.fixed(NOW, ZoneOffset.UTC)),
          transactionManager,
          Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class RecordingStore implements AnalyticsStore {
      private final List<IndicatorDefinitionVersion> published = new ArrayList<>();

      @Override
      public Optional<IndicatorDefinitionVersion> findDefinition(String key, int version) {
        return published.stream()
            .filter(value -> value.key().equals(key) && value.version() == version)
            .findFirst();
      }

      @Override
      public IndicatorDefinitionVersion publishDefinition(IndicatorDefinitionVersion definition) {
        published.add(definition);
        return definition;
      }

      @Override
      public AggregateSnapshot saveOrReplay(AggregateSnapshot snapshot) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<AggregateSnapshot> findSnapshot(org.ayuvahak.platform.core.InternalId id) {
        return Optional.empty();
      }

      @Override
      public Optional<AggregateSnapshot> findSnapshotById(UUID id) {
        return Optional.empty();
      }
    }

    private static final class RecordingAuditAppender implements AuditRecordAppender {
      private final List<AuditEvidence> records = new ArrayList<>();

      @Override
      public void appendInOwnerTransaction(
          org.ayuvahak.platform.core.InternalId id, AuditEvidence evidence, Instant recordedAt) {
        records.add(evidence);
      }

      @Override
      public void appendDurably(
          org.ayuvahak.platform.core.InternalId id, AuditEvidence evidence, Instant recordedAt) {
        records.add(evidence);
      }
    }
  }
}
