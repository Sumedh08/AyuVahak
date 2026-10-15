package org.ayuvahak.directory.application;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/**
 * Bounded owner-side orchestration for mCSD discovery and source-history reads. This class has no
 * HTTP implementation; Phase 9 supplies a configured Directory adapter through the source port.
 */
public final class DirectoryMcsdSynchronizationService {

  private static final int PAGE_SIZE = 200;
  private static final int MAX_PAGES = 25;
  private static final EnumSet<DirectoryMcsdResourceType> RESOURCE_ALLOWLIST =
      EnumSet.allOf(DirectoryMcsdResourceType.class);

  private final DirectoryMcsdSourceQueryPort sourceQuery;
  private final DirectoryMcsdSourceQueryAuthorizationPort queryAuthorization;
  private final DirectorySourceProjectionImportPort importer;
  private final DirectoryMcsdSynchronizationCheckpointPort checkpoints;
  private final Clock clock;

  public DirectoryMcsdSynchronizationService(
      DirectoryMcsdSourceQueryPort sourceQuery,
      DirectoryMcsdSourceQueryAuthorizationPort queryAuthorization,
      DirectorySourceProjectionImportPort importer,
      DirectoryMcsdSynchronizationCheckpointPort checkpoints,
      Clock clock) {
    this.sourceQuery = Objects.requireNonNull(sourceQuery, "sourceQuery");
    this.queryAuthorization = Objects.requireNonNull(queryAuthorization, "queryAuthorization");
    this.importer = Objects.requireNonNull(importer, "importer");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Performs a bounded ITI-90-style current discovery; incomplete reads never advance a cursor. */
  public DirectoryMcsdSynchronizationResult discover(
      ExecutionContext context, IssuerId sourceNamespace, int maxPages) {
    requireRequest(context, sourceNamespace, maxPages);
    queryAuthorization.authorize(
        context, sourceNamespace, DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY);
    return synchronize(context, sourceNamespace, Optional.empty(), maxPages, false);
  }

  /** Performs a bounded ITI-91-style history read from the last complete source cursor. */
  public DirectoryMcsdSynchronizationResult refresh(
      ExecutionContext context, IssuerId sourceNamespace, int maxPages) {
    requireRequest(context, sourceNamespace, maxPages);
    queryAuthorization.authorize(
        context, sourceNamespace, DirectoryMcsdSourceQueryPurpose.INCREMENTAL_HISTORY);
    Optional<String> cursor = checkpoints.findMcsdSourceCursor(sourceNamespace);
    if (cursor.isEmpty()) {
      return new DirectoryMcsdSynchronizationResult(
          DirectoryMcsdSynchronizationResult.Status.NO_CHECKPOINT, 0, 0, Optional.empty());
    }
    return synchronize(context, sourceNamespace, cursor, maxPages, true);
  }

  private DirectoryMcsdSynchronizationResult synchronize(
      ExecutionContext context,
      IssuerId sourceNamespace,
      Optional<String> startingCursor,
      int maxPages,
      boolean history) {
    int factsImported = 0;
    Optional<String> pageToken = Optional.empty();
    int pagesRead = 0;
    while (pagesRead < maxPages) {
      DirectoryMcsdSourcePage page;
      try {
        page = readPage(context, sourceNamespace, startingCursor, pageToken, history);
      } catch (DirectoryMcsdSourceUnavailableException unavailable) {
        return result(
            DirectoryMcsdSynchronizationResult.Status.SOURCE_UNAVAILABLE,
            pagesRead,
            factsImported,
            startingCursor);
      }
      pagesRead++;
      if (page.projections().size() > PAGE_SIZE
          || !belongsToSource(page.projections(), sourceNamespace)) {
        return result(
            DirectoryMcsdSynchronizationResult.Status.REJECTED,
            pagesRead,
            factsImported,
            startingCursor);
      }
      for (DirectorySourceProjection projection : page.projections()) {
        importer.importSourceProjection(context, projection);
        factsImported++;
      }
      if (page.complete()) {
        Optional<String> resultingCursor = page.nextCursor().or(() -> startingCursor);
        if (page.nextCursor().isPresent()) {
          checkpoints.saveMcsdSourceCursor(
              sourceNamespace, page.nextCursor().orElseThrow(), clock.instant());
        }
        return result(
            DirectoryMcsdSynchronizationResult.Status.COMPLETE,
            pagesRead,
            factsImported,
            resultingCursor);
      }
      if (page.nextPageToken().isEmpty()) {
        return result(
            DirectoryMcsdSynchronizationResult.Status.INCOMPLETE,
            pagesRead,
            factsImported,
            startingCursor);
      }
      pageToken = page.nextPageToken();
    }
    return result(
        DirectoryMcsdSynchronizationResult.Status.INCOMPLETE,
        pagesRead,
        factsImported,
        startingCursor);
  }

  private static void requireRequest(
      ExecutionContext context, IssuerId sourceNamespace, int maxPages) {
    Objects.requireNonNull(context, "execution context");
    Objects.requireNonNull(sourceNamespace, "sourceNamespace");
    if (maxPages < 1 || maxPages > MAX_PAGES) {
      throw new IllegalArgumentException("mCSD synchronization page bound must be from 1 to 25");
    }
  }

  private DirectoryMcsdSourcePage readPage(
      ExecutionContext context,
      IssuerId sourceNamespace,
      Optional<String> startingCursor,
      Optional<String> pageToken,
      boolean history) {
    if (history) {
      return sourceQuery.history(
          new DirectoryMcsdSourceQueryPort.HistoryRequest(
              context,
              DirectoryMcsdSourceQueryPurpose.INCREMENTAL_HISTORY,
              sourceNamespace,
              startingCursor.orElseThrow(),
              pageToken,
              PAGE_SIZE));
    }
    return sourceQuery.search(
        new DirectoryMcsdSourceQueryPort.SearchRequest(
            context,
            DirectoryMcsdSourceQueryPurpose.INITIAL_DISCOVERY,
            sourceNamespace,
            RESOURCE_ALLOWLIST,
            pageToken,
            PAGE_SIZE));
  }

  private static boolean belongsToSource(
      List<DirectorySourceProjection> projections, IssuerId sourceNamespace) {
    for (DirectorySourceProjection projection : projections) {
      if (!sourceNamespace.equals(projection.metadata().sourceIdentity().issuer())
          || resourceType(projection.metadata().sourceIdentity().resourceType()).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  private static Optional<DirectoryMcsdResourceType> resourceType(String fhirType) {
    return java.util.Arrays.stream(DirectoryMcsdResourceType.values())
        .filter(type -> type.fhirType().equals(fhirType))
        .findFirst();
  }

  private static DirectoryMcsdSynchronizationResult result(
      DirectoryMcsdSynchronizationResult.Status status,
      int pagesRead,
      int factsImported,
      Optional<String> cursor) {
    return new DirectoryMcsdSynchronizationResult(status, pagesRead, factsImported, cursor);
  }
}
