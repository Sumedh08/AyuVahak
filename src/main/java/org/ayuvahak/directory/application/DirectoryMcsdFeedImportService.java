package org.ayuvahak.directory.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.AuthenticationMechanism;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Commits Directory projection changes and their owner-scoped external replay receipt atomically.
 */
@Service
public final class DirectoryMcsdFeedImportService implements DirectoryMcsdFeedImportPort {

  private static final String OPERATION_CODE = "mcsd.iti130.feed";

  private final DirectoryMcsdFeedProjectionImportPort projectionImporter;
  private final DirectoryMcsdFeedReceiptStore receipts;
  private final AuditWriter auditWriter;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public DirectoryMcsdFeedImportService(
      DirectoryMcsdFeedProjectionImportPort projectionImporter,
      DirectoryMcsdFeedReceiptStore receipts,
      AuditWriter auditWriter,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Qualifier("auditClock") Clock clock) {
    this.projectionImporter = Objects.requireNonNull(projectionImporter, "projectionImporter");
    this.receipts = Objects.requireNonNull(receipts, "receipts");
    this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
    this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public DirectoryMcsdFeedImportResult importFeed(
      ExecutionContext context,
      IssuerId sourceNamespace,
      String externalMessageId,
      String requestFingerprint,
      List<DirectoryMcsdFeedEntry> entries) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(sourceNamespace, "sourceNamespace");
    if (externalMessageId == null
        || !externalMessageId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.MALFORMED_REQUEST,
          "A valid external feed message identifier is required.");
    }
    if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("a SHA-256 feed request fingerprint is required");
    }
    List<DirectoryMcsdFeedEntry> command = List.copyOf(Objects.requireNonNull(entries, "entries"));
    if (command.isEmpty() || command.size() > 250) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          PlatformErrorCode.VALIDATION_FAILED,
          "The Directory feed must contain between one and 250 supported resources.");
    }
    boolean[] positions = new boolean[command.size()];
    for (DirectoryMcsdFeedEntry entry : command) {
      if (entry.position() >= positions.length || positions[entry.position()]) {
        throw new IllegalArgumentException("feed entry positions must be unique and contiguous");
      }
      positions[entry.position()] = true;
    }

    FeedAttempt attempt =
        transaction.execute(
            status -> {
              receipts.configureBoundedReceiptWait();
              projectionImporter.authorizeSourceProjectionFeed(context, sourceNamespace);
              boolean claimed =
                  receipts.claim(
                      sourceNamespace,
                      externalMessageId,
                      OPERATION_CODE,
                      requestFingerprint,
                      context.correlationId().value(),
                      clock.instant());
              if (!claimed) {
                DirectoryMcsdFeedReceiptStore.Receipt existing =
                    receipts
                        .findForUpdate(sourceNamespace, externalMessageId, OPERATION_CODE)
                        .orElseThrow(
                            () ->
                                new PlatformProblemException(
                                    HttpStatus.SERVICE_UNAVAILABLE,
                                    PlatformErrorCode.DIRECTORY_UNAVAILABLE,
                                    "The prior Directory feed outcome is unavailable."));
                if (existing.requestFingerprint().equals(requestFingerprint)) {
                  return FeedAttempt.replayed(existing.outcomes());
                }
                recordReplayConflict(context);
                return FeedAttempt.conflictAttempt();
              }

              List<DirectorySourceProjectionReconciliation.Outcome> orderedOutcomes =
                  new java.util.ArrayList<>(java.util.Collections.nCopies(command.size(), null));
              command.stream()
                  .sorted(Comparator.comparingInt(DirectoryMcsdFeedImportService::projectionOrder))
                  .forEach(
                      entry -> {
                        DirectorySourceProjectionImportResult imported =
                            entry
                                .projection()
                                .map(
                                    projection ->
                                        projectionImporter.importSourceProjectionFromFeed(
                                            context, sourceNamespace, projection))
                                .orElseGet(
                                    () ->
                                        projectionImporter.importSourceDeletionFromFeed(
                                            context,
                                            sourceNamespace,
                                            entry.deletion().orElseThrow()));
                        orderedOutcomes.set(entry.position(), imported.outcome());
                      });
              List<DirectorySourceProjectionReconciliation.Outcome> outcomes =
                  List.copyOf(orderedOutcomes);
              receipts.complete(
                  sourceNamespace,
                  externalMessageId,
                  OPERATION_CODE,
                  requestFingerprint,
                  outcomes,
                  clock.instant());
              return FeedAttempt.completed(outcomes);
            });

    if (attempt.conflict()) {
      throw new PlatformProblemException(
          HttpStatus.CONFLICT,
          PlatformErrorCode.EXCHANGE_REPLAY_CONFLICT,
          "The partner message identifier was already used for different content.");
    }
    return new DirectoryMcsdFeedImportResult(attempt.outcomes(), attempt.replayed());
  }

  private void recordReplayConflict(ExecutionContext context) {
    auditWriter.recordNonMutating(
        new AuditEvidence(
            AuditActionKind.SECURITY_OUTCOME,
            Optional.of(context.actor()),
            Optional.of(context.scope()),
            "DIRECTORY_MCSD_FEED_REPLAY_CONFLICT",
            "DirectoryMcsdFeed",
            Optional.empty(),
            clock.instant(),
            AuditOutcome.DENIED,
            Optional.of("REPLAY_FINGERPRINT_CONFLICT"),
            authenticationEvidence(context.authenticationMechanism()),
            AuditAuthorizationOutcome.ALLOWED,
            context.correlationId()));
  }

  private static AuditAuthenticationEvidence authenticationEvidence(
      AuthenticationMechanism mechanism) {
    return switch (mechanism) {
      case HUMAN_ACCESS_TOKEN -> AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN;
      case SERVICE_CLIENT_CREDENTIALS -> AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN;
      case CONFIGURED_SERVICE_IDENTITY -> AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
      case LOCAL_OPERATOR_CONTROL -> AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    };
  }

  private static int projectionOrder(DirectoryMcsdFeedEntry entry) {
    if (entry.deletion().isPresent()) {
      return 5;
    }
    return switch (entry.projection().orElseThrow()) {
      case DirectorySourceProjection.OrganizationFact ignored -> 0;
      case DirectorySourceProjection.FacilityFact ignored -> 1;
      case DirectorySourceProjection.LocationFact ignored -> 2;
      case DirectorySourceProjection.HealthcareServiceFact ignored -> 3;
      case DirectorySourceProjection.EndpointMetadata ignored -> 4;
    };
  }

  private record FeedAttempt(
      boolean conflict,
      boolean replayed,
      List<DirectorySourceProjectionReconciliation.Outcome> outcomes) {
    private FeedAttempt {
      outcomes = List.copyOf(outcomes);
    }

    static FeedAttempt completed(List<DirectorySourceProjectionReconciliation.Outcome> outcomes) {
      return new FeedAttempt(false, false, outcomes);
    }

    static FeedAttempt replayed(List<DirectorySourceProjectionReconciliation.Outcome> outcomes) {
      return new FeedAttempt(false, true, outcomes);
    }

    static FeedAttempt conflictAttempt() {
      return new FeedAttempt(true, false, List.of());
    }
  }
}
