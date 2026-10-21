package org.ayuvahak.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.AnalyticsAuthorities;
import org.ayuvahak.platform.api.PlatformErrorCode;
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

class AnalyticsSnapshotReaderTest {

  private static final IssuerId DIRECTORY = new IssuerId("urn:ayuvahak:directory");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(DIRECTORY, "Organization", UUID.randomUUID().toString());
  private static final ActorReference OPERATOR =
      ActorReference.human(
          new IssuerId("https://identity.example.test/realm"), "synthetic-analyst");

  @Test
  void returnsOnlyTheAuthorizedOrganizationProjectionAndRecordsAReadAudit() {
    AnalyticsSnapshotSummary summary =
        new AnalyticsSnapshotSummary(
            UUID.randomUUID(),
            "SYN-INDICATOR-001",
            1,
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-02-01"),
            "UTC",
            AggregateCompleteness.COMPLETE,
            Optional.of(BigDecimal.ZERO),
            Instant.parse("2026-02-01T00:00:00Z"),
            Optional.of(1),
            Optional.of("PROVISIONAL"),
            Optional.of("ACKNOWLEDGED"),
            Optional.of("IMPORTED"));
    Harness harness = new Harness(true, List.of(summary));

    AnalyticsSnapshotPage page = harness.reader().list(context(), 2, 10);

    assertThat(page.items()).containsExactly(summary);
    assertThat(page.page()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(10);
    assertThat(page.hasMore()).isFalse();
    assertThat(harness.store.calls)
        .containsExactly(new StoreCall(ORGANIZATION, Optional.empty(), 20, 11));
    assertThat(harness.audit.records).hasSize(1);
  }

  @Test
  void deniedActorNeverReachesTheAnalyticsStoreOrAudit() {
    Harness harness = new Harness(false, List.of());

    assertThatThrownBy(() -> harness.reader().list(context(), 0, 20))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformProblemException) failure).code())
                    .isEqualTo(PlatformErrorCode.AUTHORIZATION_DENIED));
    assertThat(harness.store.calls).isEmpty();
    assertThat(harness.audit.records).isEmpty();
  }

  @Test
  void rejectsUnboundedPageBeforeAuthorizationOrReading() {
    Harness harness = new Harness(true, List.of());

    assertThatThrownBy(() -> harness.reader().list(context(), 0, 101))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure ->
                assertThat(((PlatformProblemException) failure).code())
                    .isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    assertThat(harness.store.calls).isEmpty();
    assertThat(harness.audit.records).isEmpty();
  }

  private static ExecutionContext context() {
    var factory = new ExecutionContextFactory((actor, scope) -> true);
    return factory.forInteractiveRequest(
        OPERATOR, RequestedExecutionScope.organization(ORGANIZATION), CorrelationId.generate());
  }

  private record StoreCall(
      ExternalReference organization,
      Optional<ExternalReference> facility,
      int offset,
      int limit) {}

  private static final class Harness {
    private final RecordingStore store = new RecordingStore();
    private final RecordingAuditAppender audit = new RecordingAuditAppender();
    private final boolean allowed;
    private final List<AnalyticsSnapshotSummary> summaries;

    private Harness(boolean allowed, List<AnalyticsSnapshotSummary> summaries) {
      this.allowed = allowed;
      this.summaries = List.copyOf(summaries);
    }

    private AnalyticsSnapshotReader reader() {
      AuthorizationGrantLookup grants = (actor, authority, scope) -> allowed;
      var authorization =
          new AuthorizationService(
              grants,
              new FunctionalAuthorityCatalog(List.of(AnalyticsAuthorities.aggregateRead())));
      return new AnalyticsSnapshotReader(
          store,
          authorization,
          new AuditWriter(
              audit, Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC)),
          Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private final class RecordingStore implements AnalyticsSnapshotReadStore {
      private final List<StoreCall> calls = new ArrayList<>();

      @Override
      public List<AnalyticsSnapshotSummary> findRecent(
          ExternalReference organization,
          Optional<ExternalReference> facility,
          int offset,
          int limit) {
        calls.add(new StoreCall(organization, facility, offset, limit));
        return summaries.stream().limit(limit).toList();
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
