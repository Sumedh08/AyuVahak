package org.ayuvahak.transfer.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.matching.application.RecommendationApplicabilityResult;
import org.ayuvahak.platform.audit.application.AuditRecordAppender;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScopeValidator;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.ayuvahak.transfer.domain.TransferAuthorities;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class TransferNextRequestFederationGuardTest {

  private static final IssuerId DIRECTORY = new IssuerId("urn:ayuvahak:directory");
  private static final IssuerId IDP = new IssuerId("https://identity.example.test/synthetic");
  private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

  @Test
  void unresolvedFederatedDestinationAttemptBlocksNextLocalAttempt() {
    boolean[] localAttemptInserted = {false};
    AuthorizationService authorization =
        new AuthorizationService(
            (actor, authority, scope) ->
                authority.code().equals(TransferAuthorities.request().code()),
            new FunctionalAuthorityCatalog(List.of(TransferAuthorities.request())));
    AuditWriter audit =
        new AuditWriter(
            new AuditRecordAppender() {
              @Override
              public void appendInOwnerTransaction(
                  InternalId id,
                  org.ayuvahak.platform.audit.domain.AuditEvidence evidence,
                  Instant recordedAt) {}

              @Override
              public void appendDurably(
                  InternalId id,
                  org.ayuvahak.platform.audit.domain.AuditEvidence evidence,
                  Instant recordedAt) {}
            },
            Clock.fixed(NOW, ZoneOffset.UTC));
    ObjectProvider<AuthorizationService> authorizationProvider =
        provider(AuthorizationService.class, authorization);
    ObjectProvider<AuditWriter> auditProvider = provider(AuditWriter.class, audit);
    TransferSettings settings =
        new TransferSettings() {
          @Override
          public java.time.Duration getRequestDeadline() {
            return java.time.Duration.ofHours(1);
          }

          @Override
          public String getExpiryWorkerClientId() {
            return "synthetic";
          }

          @Override
          public java.time.Duration getExpiryPollInterval() {
            return java.time.Duration.ofSeconds(1);
          }

          @Override
          public int getExpiryBatchSize() {
            return 10;
          }

          @Override
          public boolean isExpiryPollingEnabled() {
            return false;
          }

          @Override
          public String getReservationWorkerClientId() {
            return "synthetic";
          }

          @Override
          public String getOwnerEventConsumerClientId() {
            return "synthetic";
          }

          @Override
          public String getTransportWorkerClientId() {
            return "synthetic";
          }

          @Override
          public java.time.Duration getReservationPollInterval() {
            return java.time.Duration.ofSeconds(1);
          }

          @Override
          public java.time.Duration getReservationAttemptLease() {
            return java.time.Duration.ofSeconds(1);
          }

          @Override
          public int getReservationBatchSize() {
            return 10;
          }

          @Override
          public boolean isReservationPollingEnabled() {
            return false;
          }

          @Override
          public Optional<ReservationPolicy> reservationPolicy() {
            return Optional.empty();
          }
        };

    InternalId journeyId = InternalId.generate();
    InternalId referralId = InternalId.generate();
    InternalId recommendationId = InternalId.generate();
    ExternalReference organization = reference("Organization");
    ExternalReference originFacility = reference("Facility");
    ExternalReference localDestination = reference("Facility");
    TransferJourney journey =
        new TransferJourney(
            journeyId,
            referralId,
            organization,
            Optional.of(originFacility),
            ActorReference.human(IDP, "synthetic-origin-operator"),
            NOW.minusSeconds(300),
            false,
            4);
    TransferRequestAttempt latestLocalAttempt =
        new TransferRequestAttempt(
            InternalId.generate(),
            journeyId,
            1,
            referralId,
            2,
            recommendationId,
            reference("Facility"),
            TransferRequestAttempt.Status.WITHDRAWN,
            ActorReference.human(IDP, "synthetic-origin-operator"),
            NOW.minusSeconds(300),
            NOW.plusSeconds(3600),
            Optional.of(NOW.minusSeconds(240)),
            Optional.of(NOW.minusSeconds(240)),
            2);

    TransferStore store =
        (TransferStore)
            Proxy.newProxyInstance(
                TransferStore.class.getClassLoader(),
                new Class<?>[] {TransferStore.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "findReceipt" -> Optional.empty();
                      case "claimReceipt" ->
                          new TransferStore.ReceiptClaim(
                              TransferStore.ReceiptState.OWNER,
                              Optional.empty(),
                              Optional.empty(),
                              Optional.empty());
                      case "configureBoundedReceiptWait",
                          "lockReferralCoordination",
                          "completeReceipt" ->
                          null;
                      case "findJourney", "lockJourney" -> Optional.of(journey);
                      case "findJourneyClosure" -> Optional.empty();
                      case "findLatestRequest" -> Optional.of(latestLocalAttempt);
                      case "hasBlockingFederatedDestinationAttempt" -> true;
                      case "insertNextRequest" -> {
                        localAttemptInserted[0] = true;
                        yield true;
                      }
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    MatchingApplicabilityPort matching =
        (context, referral, recommendation, facility, revision) ->
            new RecommendationApplicabilityResult(
                recommendation,
                facility,
                RecommendationApplicabilityResult.Status.APPLICABLE,
                List.of(),
                NOW);

    TransferNextRequestCommands commands =
        new TransferNextRequestCommands(
            store,
            matching,
            authorizationProvider,
            auditProvider,
            settings,
            noOpTransactions(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(
            () ->
                commands.createNextRequest(
                    context(organization, originFacility),
                    journeyId,
                    journey.version(),
                    2,
                    recommendationId,
                    localDestination,
                    "synthetic-next-destination",
                    RequestFingerprint.fromDigest("a".repeat(64))))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class);

    org.assertj.core.api.Assertions.assertThat(localAttemptInserted[0]).isFalse();
  }

  private static ExecutionContext context(
      ExternalReference organization, ExternalReference facility) {
    ExecutionScopeValidator acceptSyntheticScope = (actor, scope) -> true;
    return new ExecutionContextFactory(acceptSyntheticScope)
        .forInteractiveRequest(
            ActorReference.human(IDP, "synthetic-origin-operator"),
            RequestedExecutionScope.facility(organization, facility),
            new CorrelationId("p11-07-origin-next-request-guard"));
  }

  private static ExternalReference reference(String type) {
    return new ExternalReference(DIRECTORY, type, InternalId.generate().toString());
  }

  private static PlatformTransactionManager noOpTransactions() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new org.springframework.transaction.support.SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
    return (ObjectProvider<T>)
        Proxy.newProxyInstance(
            ObjectProvider.class.getClassLoader(),
            new Class<?>[] {ObjectProvider.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getObject")) {
                return type.cast(value);
              }
              if (method.getName().equals("toString")) {
                return "SyntheticObjectProvider[" + type.getSimpleName() + "]";
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
