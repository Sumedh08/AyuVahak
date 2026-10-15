package org.ayuvahak.platform.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerProfileState;
import org.ayuvahak.platform.exchange.domain.PartnerProfileVersion;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration;
import org.ayuvahak.platform.idempotency.CommandReceiptKey;
import org.ayuvahak.platform.idempotency.RequestFingerprint;
import org.junit.jupiter.api.Test;

class PartnerOutboundReadServiceTest {

  private static final PartnerKey PARTNER = new PartnerKey("SYNTHETIC-DIRECTORY");
  private static final PartnerTrustConfiguration.AllowedOperation OPERATION =
      new PartnerTrustConfiguration.AllowedOperation(
          PartnerTrustConfiguration.OwnerContext.DIRECTORY,
          "mcsd.iti90.search",
          PartnerTrustConfiguration.Direction.OUTBOUND,
          PartnerTrustConfiguration.Protocol.FHIR_R4_JSON,
          "IHE-mCSD");

  @Test
  void activeProfileTokenIsPassedOnlyToTheConfiguredReadTransport() {
    PartnerProfileVersion profile = profile();
    PartnerBearerToken token =
        new PartnerBearerToken("synthetic-token", Instant.now().plusSeconds(60));
    AtomicReference<Optional<PartnerBearerToken>> seenToken = new AtomicReference<>();
    PartnerHttpTransportPort transport =
        (activeProfile, request, bearerToken) -> {
          assertThat(activeProfile).isSameAs(profile);
          assertThat(request.partnerKey()).isEqualTo(PARTNER);
          seenToken.set(bearerToken);
          return new PartnerOutboundReadResponse(
              200,
              Optional.of("application/fhir+json"),
              "synthetic-result".getBytes(java.nio.charset.StandardCharsets.UTF_8),
              activeProfile.version());
        };
    PartnerOutboundReadService service =
        new PartnerOutboundReadService(
            new Store(Optional.of(profile)),
            new PartnerAuthorizationPort() {
              @Override
              public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion activeProfile) {
                assertThat(activeProfile).isSameAs(profile);
                return Optional.of(token);
              }

              @Override
              public void invalidate(PartnerKey partnerKey) {
                throw new AssertionError("active profile must not invalidate its token");
              }
            },
            transport);

    PartnerOutboundReadResponse response = service.read(request());

    assertThat(seenToken).hasValue(Optional.of(token));
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.toString()).doesNotContain("synthetic-result", "synthetic-token");
  }

  @Test
  void activeProfilePreflightDoesNotAcquireCredentialsOrContactThePartner() {
    PartnerProfileVersion profile = profile();
    AtomicInteger credentialReads = new AtomicInteger();
    AtomicInteger transportCalls = new AtomicInteger();
    PartnerOutboundReadService service =
        new PartnerOutboundReadService(
            new Store(Optional.of(profile)),
            new PartnerAuthorizationPort() {
              @Override
              public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion activeProfile) {
                credentialReads.incrementAndGet();
                throw new AssertionError("trust preflight must not acquire credentials");
              }

              @Override
              public void invalidate(PartnerKey partnerKey) {
                throw new AssertionError("active trust profile must not invalidate credentials");
              }
            },
            (activeProfile, request, bearerToken) -> {
              transportCalls.incrementAndGet();
              throw new AssertionError("trust preflight must not contact the partner");
            });

    service.requireActive(PARTNER, OPERATION);

    assertThat(credentialReads).hasValue(0);
    assertThat(transportCalls).hasValue(0);
  }

  @Test
  void missingActiveProfileInvalidatesCachedAuthorizationBeforeFailingClosed() {
    AtomicInteger invalidations = new AtomicInteger();
    AtomicInteger transportCalls = new AtomicInteger();
    PartnerOutboundReadService service =
        new PartnerOutboundReadService(
            new Store(Optional.empty()),
            new PartnerAuthorizationPort() {
              @Override
              public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion profile) {
                throw new AssertionError("missing profile must not acquire a token");
              }

              @Override
              public void invalidate(PartnerKey partnerKey) {
                assertThat(partnerKey).isEqualTo(PARTNER);
                invalidations.incrementAndGet();
              }
            },
            (profile, request, token) -> {
              transportCalls.incrementAndGet();
              throw new AssertionError("missing profile must not make a network request");
            });

    assertThatThrownBy(() -> service.read(request()))
        .isInstanceOf(PartnerTransportException.class)
        .extracting(failure -> ((PartnerTransportException) failure).failure())
        .isEqualTo(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    assertThat(invalidations).hasValue(1);
    assertThat(transportCalls).hasValue(0);
  }

  @Test
  void retriesTransientIdempotentReadsOnlyWithinConfiguredAttemptLimit() {
    PartnerProfileVersion profile =
        profile(3, PartnerTrustConfiguration.RetryClass.IDEMPOTENT_READ);
    AtomicInteger attempts = new AtomicInteger();
    PartnerOutboundReadService service =
        new PartnerOutboundReadService(
            new Store(Optional.of(profile)),
            authorizationWithoutToken(),
            (activeProfile, request, bearerToken) -> {
              if (attempts.incrementAndGet() < 3) {
                throw new PartnerTransportException(PartnerTransportFailure.REMOTE_5XX);
              }
              return new PartnerOutboundReadResponse(
                  200, Optional.of("application/fhir+json"), new byte[0], activeProfile.version());
            });

    PartnerOutboundReadResponse response = service.read(request());

    assertThat(response.status()).isEqualTo(200);
    assertThat(attempts).hasValue(3);
  }

  @Test
  void doesNotRetryPermanentRemoteFailuresOrExceedAttemptLimit() {
    PartnerProfileVersion profile =
        profile(3, PartnerTrustConfiguration.RetryClass.IDEMPOTENT_READ);
    AtomicInteger permanentAttempts = new AtomicInteger();
    PartnerOutboundReadService permanentFailure =
        new PartnerOutboundReadService(
            new Store(Optional.of(profile)),
            authorizationWithoutToken(),
            (activeProfile, request, bearerToken) -> {
              permanentAttempts.incrementAndGet();
              throw new PartnerTransportException(PartnerTransportFailure.REMOTE_4XX);
            });

    assertThatThrownBy(() -> permanentFailure.read(request()))
        .isInstanceOf(PartnerTransportException.class);
    assertThat(permanentAttempts).hasValue(1);

    PartnerProfileVersion twoAttemptProfile =
        profile(2, PartnerTrustConfiguration.RetryClass.IDEMPOTENT_READ);
    AtomicInteger transientAttempts = new AtomicInteger();
    PartnerOutboundReadService capped =
        new PartnerOutboundReadService(
            new Store(Optional.of(twoAttemptProfile)),
            authorizationWithoutToken(),
            (activeProfile, request, bearerToken) -> {
              transientAttempts.incrementAndGet();
              throw new PartnerTransportException(PartnerTransportFailure.REMOTE_5XX);
            });

    assertThatThrownBy(() -> capped.read(request()))
        .isInstanceOf(PartnerTransportException.class)
        .extracting(failure -> ((PartnerTransportException) failure).failure())
        .isEqualTo(PartnerTransportFailure.REMOTE_5XX);
    assertThat(transientAttempts).hasValue(2);
  }

  @Test
  void queuedOutboundReadRechecksTrustAfterPartnerRevocation() throws Exception {
    PartnerProfileVersion profile = profile(1, PartnerTrustConfiguration.RetryClass.NEVER);
    Store store = new Store(Optional.of(profile));
    CountDownLatch queued = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger socketAttempts = new AtomicInteger();
    PartnerAuthorizationPort authorization =
        new PartnerAuthorizationPort() {
          @Override
          public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion activeProfile) {
            return Optional.empty();
          }

          @Override
          public Optional<PartnerBearerToken> bearerToken(
              PartnerProfileVersion activeProfile, Runnable beforeAttempt) {
            return Optional.empty();
          }

          @Override
          public void invalidate(PartnerKey partnerKey) {}
        };
    PartnerHttpTransportPort transport =
        new PartnerHttpTransportPort() {
          @Override
          public PartnerOutboundReadResponse execute(
              PartnerProfileVersion activeProfile,
              PartnerOutboundReadRequest request,
              Optional<PartnerBearerToken> bearerToken) {
            throw new AssertionError("the trust-checking overload is required");
          }

          @Override
          public PartnerOutboundReadResponse execute(
              PartnerProfileVersion activeProfile,
              PartnerOutboundReadRequest request,
              Optional<PartnerBearerToken> bearerToken,
              Runnable beforeAttempt) {
            queued.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("queued outbound attempt was not released");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new AssertionError("test worker was interrupted", interrupted);
            }
            beforeAttempt.run();
            socketAttempts.incrementAndGet();
            return new PartnerOutboundReadResponse(
                200, Optional.of("application/fhir+json"), new byte[0], activeProfile.version());
          }
        };
    PartnerOutboundReadService service =
        new PartnerOutboundReadService(store, authorization, transport);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread requestThread =
        new Thread(
            () -> {
              try {
                service.read(request());
              } catch (Throwable caught) {
                failure.set(caught);
              }
            });
    requestThread.start();

    assertThat(queued.await(5, TimeUnit.SECONDS)).isTrue();
    store.activeProfile.set(Optional.empty());
    release.countDown();
    requestThread.join(5_000);

    assertThat(requestThread.isAlive()).isFalse();
    assertThat(failure.get())
        .isInstanceOf(PartnerTransportException.class)
        .extracting(caught -> ((PartnerTransportException) caught).failure())
        .isEqualTo(PartnerTransportFailure.PROFILE_NOT_ACTIVE);
    assertThat(socketAttempts).hasValue(0);
  }

  private static PartnerAuthorizationPort authorizationWithoutToken() {
    return new PartnerAuthorizationPort() {
      @Override
      public Optional<PartnerBearerToken> bearerToken(PartnerProfileVersion profile) {
        return Optional.empty();
      }

      @Override
      public void invalidate(PartnerKey partnerKey) {}
    };
  }

  private static PartnerOutboundReadRequest request() {
    return new PartnerOutboundReadRequest(
        PARTNER, OPERATION, List.of("Organization"), Map.of("active", List.of("true")));
  }

  private static PartnerProfileVersion profile() {
    return profile(2, PartnerTrustConfiguration.RetryClass.IDEMPOTENT_READ);
  }

  private static PartnerProfileVersion profile(
      int maxAttempts, PartnerTrustConfiguration.RetryClass retryClass) {
    Instant now = Instant.now();
    PartnerTrustConfiguration configuration =
        new PartnerTrustConfiguration(
            PARTNER,
            new ExternalReference(
                new IssuerId("urn:synthetic:directory"), "Organization", "SYN-ORG-001"),
            "https://partner.example.test",
            "/fhir",
            new PartnerTrustConfiguration.Authentication(
                PartnerTrustConfiguration.AuthenticationMethod.OAUTH_CLIENT_CREDENTIALS,
                Optional.of("env:SYNTHETIC_CLIENT_CREDENTIAL"),
                Optional.of("env:SYNTHETIC_TOKEN_KEYS"),
                Optional.of("https://identity.example.test"),
                Optional.of("/oauth/token"),
                Optional.of("https://identity.example.test/issuer"),
                Optional.of("synthetic-resource"),
                Optional.of("synthetic-client")),
            PartnerTrustConfiguration.TlsPolicy.SYSTEM_CA,
            Optional.empty(),
            new PartnerTrustConfiguration.Limits(
                1_000, 2_000, 5_000, 1_000, 2_000, 1, 10, 5, maxAttempts),
            retryClass,
            List.of(OPERATION));
    return new PartnerProfileVersion(
        InternalId.generate(), 1, PartnerProfileState.ACTIVE, configuration, now, now);
  }

  private static final class Store implements PartnerTrustProfileStore {

    private final AtomicReference<Optional<PartnerProfileVersion>> activeProfile;

    private Store(Optional<PartnerProfileVersion> activeProfile) {
      this.activeProfile = new AtomicReference<>(activeProfile);
    }

    @Override
    public Optional<PartnerProfileVersion> findActive(
        PartnerKey partnerKey, PartnerTrustConfiguration.AllowedOperation operation) {
      assertThat(partnerKey).isEqualTo(PARTNER);
      assertThat(operation).isEqualTo(OPERATION);
      return activeProfile.get();
    }

    @Override
    public Optional<PartnerProfileVersion> findActiveInbound(
        org.ayuvahak.platform.core.ActorReference principal,
        PartnerTrustConfiguration.AllowedOperation operation) {
      throw unexpected();
    }

    @Override
    public void configureBoundedReceiptWait() {
      throw unexpected();
    }

    @Override
    public PartnerProfileVersion createInitial(
        PartnerTrustConfiguration configuration, Instant now) {
      throw unexpected();
    }

    @Override
    public PartnerProfileVersion createNextVersion(
        PartnerTrustConfiguration configuration, int expectedCurrentVersion, Instant now) {
      throw unexpected();
    }

    @Override
    public PartnerProfileVersion activate(PartnerKey partnerKey, int version, Instant now) {
      throw unexpected();
    }

    @Override
    public PartnerProfileVersion suspend(PartnerKey partnerKey, Instant now) {
      throw unexpected();
    }

    @Override
    public PartnerProfileVersion revoke(PartnerKey partnerKey, Instant now) {
      throw unexpected();
    }

    @Override
    public List<PartnerProfileVersion> versions(PartnerKey partnerKey) {
      throw unexpected();
    }

    @Override
    public Optional<PartnerProfileVersion> findById(InternalId versionId) {
      throw unexpected();
    }

    @Override
    public ReceiptClaim claimReceipt(
        CommandReceiptKey key, RequestFingerprint fingerprint, Instant now) {
      throw unexpected();
    }

    @Override
    public void completeReceipt(
        CommandReceiptKey key, int responseStatus, InternalId resultVersion, Instant now) {
      throw unexpected();
    }

    private static AssertionError unexpected() {
      return new AssertionError("unexpected trust-store operation");
    }
  }
}
