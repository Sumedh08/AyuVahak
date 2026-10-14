package org.ayuvahak.matching.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.capacity.application.CapacityReadinessBatchEvaluation;
import org.ayuvahak.directory.application.DirectoryMatchingCandidatePage;
import org.ayuvahak.matching.domain.RecommendationSnapshot;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.PhaseTwoAuthorities;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.referral.application.ReferralMatchingRequirement;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MatchingRecommendationQueriesReferralBindingTest {

  private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
  private static final IssuerId DIRECTORY_ISSUER = new IssuerId("urn:ayuvahak:directory");
  private static final IssuerId IDENTITY_ISSUER = new IssuerId("urn:ayuvahak:identity");
  private static final ExternalReference ORGANIZATION = reference("Organization");
  private static final ExternalReference ORIGIN_FACILITY = reference("Facility");

  @Test
  void referralMismatchReturnsNotApplicableBeforeAnyOwnerEvidenceRead() {
    InternalId storedReferralId = InternalId.generate();
    InternalId requestedReferralId = InternalId.generate();
    InternalId snapshotId = InternalId.generate();
    RecommendationSnapshot snapshot = snapshot(snapshotId, storedReferralId);
    TrackingTransactionManager transactionManager = new TrackingTransactionManager();
    CountingStore store = new CountingStore(snapshot, transactionManager);
    int[] ownerReadCalls = {0};
    MatchingApplicabilityEvidencePort ownerEvidence =
        new MatchingApplicabilityEvidencePort() {
          @Override
          public ReferralMatchingRequirement readSubmittedRequirement(
              CorrelationId correlationId,
              ExternalReference organization,
              Optional<ExternalReference> facility,
              InternalId referralId,
              long revisionNumber) {
            ownerReadCalls[0]++;
            throw new AssertionError("Referral must not be read for a mismatched snapshot");
          }

          @Override
          public Optional<DirectoryMatchingCandidatePage.Candidate> readCandidate(
              CorrelationId correlationId,
              ExternalReference organization,
              ExternalReference facility,
              List<String> requiredCapabilityCodes,
              Instant evaluationTime) {
            ownerReadCalls[0]++;
            throw new AssertionError("Directory must not be read for a mismatched snapshot");
          }

          @Override
          public CapacityReadinessBatchEvaluation evaluateCandidateCapacityReadiness(
              CorrelationId correlationId,
              ExternalReference organization,
              ExternalReference facility,
              List<String> requiredDimensionCodes,
              Instant evaluationTime) {
            ownerReadCalls[0]++;
            throw new AssertionError("Capacity must not be read for a mismatched snapshot");
          }
        };
    int[] policyLookups = {0};
    int[] durableAuditWrites = {0};
    MatchingRecommendationQueries queries =
        new MatchingRecommendationQueries(
            store,
            ownerEvidence,
            () -> {
              policyLookups[0]++;
              return Optional.empty();
            },
            new AuthorizationService(
                (actor, authority, scope) -> false, PhaseTwoAuthorities.catalog()),
            new AuditWriter(
                new AuditRecordAppender() {
                  @Override
                  public void appendInOwnerTransaction(
                      InternalId id, AuditEvidence evidence, Instant recordedAt) {
                    throw new AssertionError("a read must not append mutation audit");
                  }

                  @Override
                  public void appendDurably(
                      InternalId id, AuditEvidence evidence, Instant recordedAt) {
                    durableAuditWrites[0]++;
                  }
                },
                Clock.fixed(NOW, ZoneOffset.UTC)),
            transactionManager,
            Clock.fixed(NOW, ZoneOffset.UTC));

    RecommendationApplicabilityResult result =
        queries.revalidateRecommendationApplicability(
            matchingCaller(), snapshotId, requestedReferralId, reference("Facility"), 1);

    assertThat(result.status()).isEqualTo(RecommendationApplicabilityResult.Status.NOT_APPLICABLE);
    assertThat(result.reasonCodes()).containsExactly("REFERRAL_ID_MISMATCH");
    assertThat(store.snapshotReads).isEqualTo(1);
    assertThat(ownerReadCalls[0]).isZero();
    assertThat(policyLookups[0]).isZero();
    assertThat(durableAuditWrites[0]).isEqualTo(1);
  }

  @Test
  void recommendationSnapshotReadUsesItsOwnTransactionInsideTransferTransaction() {
    InternalId storedReferralId = InternalId.generate();
    InternalId requestedReferralId = InternalId.generate();
    InternalId snapshotId = InternalId.generate();
    TrackingTransactionManager transactionManager = new TrackingTransactionManager();
    CountingStore store =
        new CountingStore(snapshot(snapshotId, storedReferralId), transactionManager);
    MatchingRecommendationQueries queries =
        new MatchingRecommendationQueries(
            store,
            emptyOwnerEvidence(),
            () -> Optional.empty(),
            new AuthorizationService(
                (actor, authority, scope) -> false, PhaseTwoAuthorities.catalog()),
            new AuditWriter(
                new AuditRecordAppender() {
                  @Override
                  public void appendInOwnerTransaction(
                      InternalId id, AuditEvidence evidence, Instant recordedAt) {}

                  @Override
                  public void appendDurably(
                      InternalId id, AuditEvidence evidence, Instant recordedAt) {}
                },
                Clock.fixed(NOW, ZoneOffset.UTC)),
            transactionManager,
            Clock.fixed(NOW, ZoneOffset.UTC));

    TransactionStatus transferTransaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());
    RecommendationApplicabilityResult result =
        queries.revalidateRecommendationApplicability(
            matchingCaller(), snapshotId, requestedReferralId, reference("Facility"), 1);

    assertThat(result.status()).isEqualTo(RecommendationApplicabilityResult.Status.NOT_APPLICABLE);
    assertThat(store.snapshotReadTransactionDepth).isEqualTo(2);
    assertThat(transactionManager.depth()).isEqualTo(1);
    transactionManager.commit(transferTransaction);
    assertThat(transactionManager.depth()).isZero();
  }

  private static MatchingApplicabilityEvidencePort emptyOwnerEvidence() {
    return new MatchingApplicabilityEvidencePort() {
      @Override
      public ReferralMatchingRequirement readSubmittedRequirement(
          CorrelationId correlationId,
          ExternalReference organization,
          Optional<ExternalReference> facility,
          InternalId referralId,
          long revisionNumber) {
        throw new AssertionError("Owner evidence must not be read for a mismatched snapshot");
      }

      @Override
      public Optional<DirectoryMatchingCandidatePage.Candidate> readCandidate(
          CorrelationId correlationId,
          ExternalReference organization,
          ExternalReference facility,
          List<String> requiredCapabilityCodes,
          Instant evaluationTime) {
        throw new AssertionError("Owner evidence must not be read for a mismatched snapshot");
      }

      @Override
      public CapacityReadinessBatchEvaluation evaluateCandidateCapacityReadiness(
          CorrelationId correlationId,
          ExternalReference organization,
          ExternalReference facility,
          List<String> requiredDimensionCodes,
          Instant evaluationTime) {
        throw new AssertionError("Owner evidence must not be read for a mismatched snapshot");
      }
    };
  }

  private static ExecutionContext matchingCaller() {
    ActorReference service = ActorReference.service(IDENTITY_ISSUER, "matching-service");
    return new ExecutionContextFactory((actor, scope) -> true)
        .forIntegrationRequest(
            service,
            RequestedExecutionScope.facility(ORGANIZATION, ORIGIN_FACILITY),
            new CorrelationId("matching-referral-binding-test"));
  }

  private static RecommendationSnapshot snapshot(InternalId snapshotId, InternalId referralId) {
    return new RecommendationSnapshot(
        snapshotId,
        referralId,
        1,
        NOW.minusSeconds(5),
        NOW.plusSeconds(30),
        new RecommendationSnapshot.PolicyVersion(reference("MatchingPolicy"), 1),
        new RecommendationSnapshot.PolicyVersion(
            reference("CapabilityOperationalRequirementPolicy"), 1),
        List.of(),
        RecommendationSnapshot.EvaluationOutcome.INDETERMINATE,
        List.of("OWNER_EVIDENCE_UNAVAILABLE"),
        List.of(),
        new RecommendationSnapshot.Attribution(
            ActorReference.human(IDENTITY_ISSUER, "synthetic-operator"),
            ORGANIZATION,
            Optional.of(ORIGIN_FACILITY),
            new CorrelationId("matching-snapshot-binding-test"),
            "MATCHING_EVALUATION"));
  }

  private static ExternalReference reference(String resourceType) {
    return new ExternalReference(DIRECTORY_ISSUER, resourceType, InternalId.generate().toString());
  }

  private static final class CountingStore implements MatchingStore {

    private final RecommendationSnapshot snapshot;
    private final TrackingTransactionManager transactionManager;
    private int snapshotReads;
    private int snapshotReadTransactionDepth;

    private CountingStore(
        RecommendationSnapshot snapshot, TrackingTransactionManager transactionManager) {
      this.snapshot = snapshot;
      this.transactionManager = transactionManager;
    }

    @Override
    public void insertSnapshot(RecommendationSnapshot snapshot) {
      throw new UnsupportedOperationException("not used by applicability query");
    }

    @Override
    public Optional<RecommendationSnapshot> findSnapshot(InternalId recommendationId) {
      snapshotReads++;
      snapshotReadTransactionDepth = transactionManager.depth();
      return recommendationId.equals(snapshot.id()) ? Optional.of(snapshot) : Optional.empty();
    }

    @Override
    public List<RecommendationSnapshot> findHistory(
        InternalId referralId,
        InternalId originOrganizationId,
        Optional<InternalId> originFacilityId,
        int limit) {
      throw new UnsupportedOperationException("not used by applicability query");
    }

    @Override
    public void configureBoundedReceiptWait() {
      throw new UnsupportedOperationException("not used by applicability query");
    }

    @Override
    public Optional<ReceiptClaim> findReceipt(
        CommandReceiptKey key, RequestFingerprint fingerprint) {
      throw new UnsupportedOperationException("not used by applicability query");
    }

    @Override
    public ReceiptClaim claimReceipt(
        CommandReceiptKey key, RequestFingerprint fingerprint, Instant recordedAt) {
      throw new UnsupportedOperationException("not used by applicability query");
    }

    @Override
    public void completeReceipt(
        CommandReceiptKey key, int responseStatus, InternalId resultReference, Instant recordedAt) {
      throw new UnsupportedOperationException("not used by applicability query");
    }
  }

  private static final class TrackingTransactionManager implements PlatformTransactionManager {

    private final Deque<Integer> previousDepths = new ArrayDeque<>();
    private int depth;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      boolean startsTransaction =
          depth == 0
              || definition.getPropagationBehavior()
                  == TransactionDefinition.PROPAGATION_REQUIRES_NEW;
      if (!startsTransaction) {
        return new SimpleTransactionStatus(false);
      }
      previousDepths.push(depth);
      depth++;
      return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {
      complete(status);
    }

    @Override
    public void rollback(TransactionStatus status) {
      complete(status);
    }

    int depth() {
      return depth;
    }

    private void complete(TransactionStatus status) {
      if (status.isNewTransaction()) {
        depth = previousDepths.pop();
      }
    }
  }
}
