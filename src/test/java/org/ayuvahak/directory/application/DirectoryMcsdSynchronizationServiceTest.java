package org.ayuvahak.directory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.junit.jupiter.api.Test;

class DirectoryMcsdSynchronizationServiceTest {

  private static final IssuerId SOURCE = new IssuerId("urn:synthetic:mcsd:source-a");
  private static final String FINGERPRINT = "a".repeat(64);
  private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
  private final ExecutionContext context = executionContext();

  @Test
  void discoveryAndHistoryUseSeparateReadOperationsAndAdvanceOnlyCompleteCursors() {
    ArrayDeque<DirectoryMcsdSourcePage> searchPages = new ArrayDeque<>();
    searchPages.add(
        new DirectoryMcsdSourcePage(
            List.of(organization("org-a")), Optional.of("page-2"), Optional.empty(), false));
    searchPages.add(
        new DirectoryMcsdSourcePage(
            List.of(organization("org-b")), Optional.empty(), Optional.of("opaque:v1"), true));
    ArrayDeque<DirectoryMcsdSourcePage> historyPages = new ArrayDeque<>();
    historyPages.add(
        new DirectoryMcsdSourcePage(
            List.of(organization("org-c")), Optional.empty(), Optional.of("opaque:v2"), true));
    List<DirectoryMcsdSourceQueryPort.SearchRequest> searchRequests = new ArrayList<>();
    List<DirectoryMcsdSourceQueryPort.HistoryRequest> historyRequests = new ArrayList<>();
    DirectoryMcsdSourceQueryPort source =
        new DirectoryMcsdSourceQueryPort() {
          @Override
          public DirectoryMcsdSourcePage search(SearchRequest request) {
            searchRequests.add(request);
            return searchPages.removeFirst();
          }

          @Override
          public DirectoryMcsdSourcePage history(HistoryRequest request) {
            historyRequests.add(request);
            return historyPages.removeFirst();
          }
        };
    List<AuthorizedQuery> authorizedQueries = new ArrayList<>();
    DirectoryMcsdSourceQueryAuthorizationPort authorization =
        (executionContext, sourceNamespace, purpose) ->
            authorizedQueries.add(new AuthorizedQuery(executionContext, sourceNamespace, purpose));
    List<DirectorySourceProjection> imported = new ArrayList<>();
    DirectorySourceProjectionImportPort importer =
        (executionContext, projection) -> {
          imported.add(projection);
          return new DirectorySourceProjectionImportResult(
              DirectorySourceProjectionReconciliation.Outcome.CREATE,
              Optional.empty(),
              Optional.empty());
        };
    RecordingCheckpoints checkpoints = new RecordingCheckpoints(Optional.of("opaque:v1"));
    DirectoryMcsdSynchronizationService service =
        new DirectoryMcsdSynchronizationService(
            source, authorization, importer, checkpoints, Clock.fixed(NOW, ZoneOffset.UTC));

    DirectoryMcsdSynchronizationResult discovery = service.discover(context, SOURCE, 5);
    DirectoryMcsdSynchronizationResult refresh = service.refresh(context, SOURCE, 5);

    assertThat(discovery.status()).isEqualTo(DirectoryMcsdSynchronizationResult.Status.COMPLETE);
    assertThat(discovery.pagesRead()).isEqualTo(2);
    assertThat(discovery.factsImported()).isEqualTo(2);
    assertThat(discovery.sourceCursor()).contains("opaque:v1");
    assertThat(searchRequests)
        .extracting(DirectoryMcsdSourceQueryPort.SearchRequest::pageToken)
        .containsExactly(Optional.empty(), Optional.of("page-2"));
    assertThat(searchRequests.getFirst().resourceTypes())
        .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(DirectoryMcsdResourceType.class));
    assertThat(searchRequests.getFirst().executionContext()).isSameAs(context);
    assertThat(searchRequests.getFirst().purpose())
        .isEqualTo(DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY);
    assertThat(searchRequests.getFirst().pageSize()).isEqualTo(200);
    assertThat(refresh.status()).isEqualTo(DirectoryMcsdSynchronizationResult.Status.COMPLETE);
    assertThat(refresh.factsImported()).isEqualTo(1);
    assertThat(historyRequests)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.sinceCursor()).isEqualTo("opaque:v1");
              assertThat(request.executionContext()).isSameAs(context);
              assertThat(request.purpose())
                  .isEqualTo(DirectoryMcsdSourceQueryPurpose.INCREMENTAL_HISTORY);
              assertThat(request.pageToken()).isEmpty();
            });
    assertThat(authorizedQueries)
        .containsExactly(
            new AuthorizedQuery(context, SOURCE, DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY),
            new AuthorizedQuery(
                context, SOURCE, DirectoryMcsdSourceQueryPurpose.INCREMENTAL_HISTORY));
    assertThat(imported)
        .extracting(projection -> projection.metadata().sourceIdentity().value())
        .containsExactly("org-a", "org-b", "org-c");
    assertThat(checkpoints.savedCursors)
        .containsExactly(
            new SavedCursor(SOURCE, "opaque:v1", NOW), new SavedCursor(SOURCE, "opaque:v2", NOW));
  }

  @Test
  void incompleteDiscoveryImportsReceivedFactsButDoesNotAdvanceCursor() {
    DirectoryMcsdSourceQueryPort source =
        new DirectoryMcsdSourceQueryPort() {
          @Override
          public DirectoryMcsdSourcePage search(SearchRequest request) {
            return new DirectoryMcsdSourcePage(
                List.of(organization("org-partial")), Optional.empty(), Optional.empty(), false);
          }

          @Override
          public DirectoryMcsdSourcePage history(HistoryRequest request) {
            throw new AssertionError("history is not used for initial discovery");
          }
        };
    List<DirectorySourceProjection> imported = new ArrayList<>();
    DirectorySourceProjectionImportPort importer =
        (executionContext, projection) -> {
          imported.add(projection);
          return new DirectorySourceProjectionImportResult(
              DirectorySourceProjectionReconciliation.Outcome.CREATE,
              Optional.empty(),
              Optional.empty());
        };
    RecordingCheckpoints checkpoints = new RecordingCheckpoints(Optional.empty());
    DirectoryMcsdSynchronizationService service =
        new DirectoryMcsdSynchronizationService(
            source,
            (executionContext, namespace, purpose) -> {},
            importer,
            checkpoints,
            Clock.fixed(NOW, ZoneOffset.UTC));

    DirectoryMcsdSynchronizationResult result = service.discover(context, SOURCE, 5);

    assertThat(result.status()).isEqualTo(DirectoryMcsdSynchronizationResult.Status.INCOMPLETE);
    assertThat(result.sourceCursor()).isEmpty();
    assertThat(imported).hasSize(1);
    assertThat(checkpoints.savedCursors).isEmpty();
  }

  @Test
  void rejectsFactsFromAnotherSourceNamespaceBeforeImport() {
    DirectoryMcsdSourceQueryPort source =
        new DirectoryMcsdSourceQueryPort() {
          @Override
          public DirectoryMcsdSourcePage search(SearchRequest request) {
            return new DirectoryMcsdSourcePage(
                List.of(organization("org-foreign", "urn:synthetic:mcsd:other")),
                Optional.empty(),
                Optional.of("opaque:v1"),
                true);
          }

          @Override
          public DirectoryMcsdSourcePage history(HistoryRequest request) {
            throw new AssertionError("history is not used for initial discovery");
          }
        };
    List<DirectorySourceProjection> imported = new ArrayList<>();
    DirectorySourceProjectionImportPort importer = importer(imported);
    RecordingCheckpoints checkpoints = new RecordingCheckpoints(Optional.empty());
    DirectoryMcsdSynchronizationService service =
        new DirectoryMcsdSynchronizationService(
            source,
            (executionContext, namespace, purpose) -> {},
            importer,
            checkpoints,
            Clock.fixed(NOW, ZoneOffset.UTC));

    DirectoryMcsdSynchronizationResult result = service.discover(context, SOURCE, 5);

    assertThat(result.status()).isEqualTo(DirectoryMcsdSynchronizationResult.Status.REJECTED);
    assertThat(result.sourceCursor()).isEmpty();
    assertThat(imported).isEmpty();
    assertThat(checkpoints.savedCursors).isEmpty();
  }

  @Test
  void deniedDiscoveryDoesNotQuerySourceEvenWhenSourceWouldReturnNoFacts() {
    List<String> sourceCalls = new ArrayList<>();
    DirectoryMcsdSourceQueryPort source =
        new DirectoryMcsdSourceQueryPort() {
          @Override
          public DirectoryMcsdSourcePage search(SearchRequest request) {
            sourceCalls.add("search");
            return new DirectoryMcsdSourcePage(List.of(), Optional.empty(), Optional.empty(), true);
          }

          @Override
          public DirectoryMcsdSourcePage history(HistoryRequest request) {
            sourceCalls.add("history");
            return new DirectoryMcsdSourcePage(List.of(), Optional.empty(), Optional.empty(), true);
          }
        };
    RuntimeException denied = new SecurityException("Directory curation authority denied");
    DirectoryMcsdSourceQueryAuthorizationPort authorization =
        (executionContext, sourceNamespace, purpose) -> {
          assertThat(executionContext).isSameAs(context);
          assertThat(sourceNamespace).isEqualTo(SOURCE);
          assertThat(purpose).isEqualTo(DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY);
          throw denied;
        };
    DirectoryMcsdSynchronizationService service =
        new DirectoryMcsdSynchronizationService(
            source,
            authorization,
            (executionContext, projection) -> {
              throw new AssertionError("an empty source response imports no projection");
            },
            new RecordingCheckpoints(Optional.empty()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.discover(context, SOURCE, 1)).isSameAs(denied);
    assertThat(sourceCalls).isEmpty();
  }

  @Test
  void deniedHistoryDoesNotReadCheckpointOrQuerySource() {
    AtomicInteger sourceCalls = new AtomicInteger();
    AtomicInteger checkpointReads = new AtomicInteger();
    DirectoryMcsdSourceQueryPort source =
        new DirectoryMcsdSourceQueryPort() {
          @Override
          public DirectoryMcsdSourcePage search(SearchRequest request) {
            sourceCalls.incrementAndGet();
            throw new AssertionError("denied history must not query the source");
          }

          @Override
          public DirectoryMcsdSourcePage history(HistoryRequest request) {
            sourceCalls.incrementAndGet();
            throw new AssertionError("denied history must not query the source");
          }
        };
    RuntimeException denied = new SecurityException("Directory curation authority denied");
    DirectoryMcsdSourceQueryAuthorizationPort authorization =
        (executionContext, sourceNamespace, purpose) -> {
          assertThat(purpose).isEqualTo(DirectoryMcsdSourceQueryPurpose.INCREMENTAL_HISTORY);
          throw denied;
        };
    DirectoryMcsdSynchronizationCheckpointPort checkpoints =
        new DirectoryMcsdSynchronizationCheckpointPort() {
          @Override
          public Optional<String> findMcsdSourceCursor(IssuerId sourceNamespace) {
            checkpointReads.incrementAndGet();
            return Optional.of("opaque:v1");
          }

          @Override
          public void saveMcsdSourceCursor(
              IssuerId sourceNamespace, String cursor, Instant recordedAt) {
            throw new AssertionError("denied history must not advance a cursor");
          }
        };
    DirectoryMcsdSynchronizationService service =
        new DirectoryMcsdSynchronizationService(
            source,
            authorization,
            importer(List.of()),
            checkpoints,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.refresh(context, SOURCE, 1)).isSameAs(denied);
    assertThat(checkpointReads).hasValue(0);
    assertThat(sourceCalls).hasValue(0);
  }

  private static DirectorySourceProjectionImportPort importer(
      List<DirectorySourceProjection> imported) {
    return (executionContext, projection) -> {
      imported.add(projection);
      return new DirectorySourceProjectionImportResult(
          DirectorySourceProjectionReconciliation.Outcome.CREATE,
          Optional.empty(),
          Optional.empty());
    };
  }

  private static ExecutionContext executionContext() {
    return new ExecutionContextFactory((actor, scope) -> true)
        .forDeploymentBoundedBackgroundWork(
            ActorReference.service(new IssuerId("urn:synthetic:ayuvahak"), "mcsd-sync-test"),
            new CorrelationId("mcsd-sync-test"));
  }

  private static final class RecordingCheckpoints
      implements DirectoryMcsdSynchronizationCheckpointPort {

    private final Optional<String> cursor;
    private final List<SavedCursor> savedCursors = new ArrayList<>();

    private RecordingCheckpoints(Optional<String> cursor) {
      this.cursor = cursor;
    }

    @Override
    public Optional<String> findMcsdSourceCursor(IssuerId sourceNamespace) {
      assertThat(sourceNamespace).isEqualTo(SOURCE);
      return cursor;
    }

    @Override
    public void saveMcsdSourceCursor(IssuerId sourceNamespace, String value, Instant recordedAt) {
      savedCursors.add(new SavedCursor(sourceNamespace, value, recordedAt));
    }
  }

  private record SavedCursor(IssuerId sourceNamespace, String value, Instant recordedAt) {}

  private record AuthorizedQuery(
      ExecutionContext context,
      IssuerId sourceNamespace,
      DirectoryMcsdSourceQueryPurpose purpose) {}

  private static DirectorySourceProjection.OrganizationFact organization(String id) {
    return organization(id, SOURCE.value());
  }

  private static DirectorySourceProjection.OrganizationFact organization(String id, String source) {
    return new DirectorySourceProjection.OrganizationFact(
        new DirectorySourceProjection.Metadata(
            new ExternalReference(new IssuerId(source), "Organization", id),
            Optional.empty(),
            Optional.empty(),
            false,
            "urn:synthetic:mcsd:profile:organization",
            FINGERPRINT),
        "Synthetic " + id,
        Optional.empty(),
        true);
  }
}
