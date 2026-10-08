package org.ayuvahak.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.authorization.domain.PhaseTwoAuthorities;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class AuthorizationServiceTest {

  private static final ActorReference ACTOR =
      ActorReference.human(
          new IssuerId("https://identity.example.test/realm"), "synthetic-user-17");

  @Test
  void onlyTheThreePlatformAuthoritiesAreRegisteredForProduction() {
    var authorities = PhaseTwoAuthorities.catalog();

    assertThat(authorities.find("AccessGrantAdministration"))
        .contains(PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION);
    assertThat(authorities.find("AuditReview")).contains(PhaseTwoAuthorities.AUDIT_REVIEW);
    assertThat(authorities.find("EventDeliveryOperations"))
        .contains(PhaseTwoAuthorities.EVENT_DELIVERY_OPERATIONS);
    assertThat(authorities.find("ClinicalSuitabilityAuthority")).isEmpty();
    assertThat(authorities.find(null)).isEmpty();
    assertThat(PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION.allowedScopeKinds())
        .containsExactly(org.ayuvahak.platform.executioncontext.ExecutionScope.Kind.PLATFORM);
  }

  @Test
  void missingGrantDeniesWithoutLeakingStorageDetails() {
    AuthorizationService service =
        new AuthorizationService((actor, authority, scope) -> false, PhaseTwoAuthorities.catalog());

    AuthorizationDecision decision =
        service.evaluate(context(), PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION.code());

    assertThat(decision.isAllowed()).isFalse();
    assertThat(decision.denialReason())
        .contains(AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
    assertThat(decision.toString()).doesNotContain(ACTOR.identity().value());
  }

  @Test
  void databaseUncertaintyProducesAnExplicitDeny() {
    AuthorizationGrantLookup unavailable =
        (actor, authority, scope) -> {
          throw new DataAccessResourceFailureException("synthetic database outage");
        };
    AuthorizationService service =
        new AuthorizationService(unavailable, PhaseTwoAuthorities.catalog());

    AuthorizationDecision decision =
        service.evaluate(context(), PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION.code());

    assertThat(decision.isAllowed()).isFalse();
    assertThat(decision.denialReason())
        .contains(AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE);
    assertThat(decision.toString()).doesNotContain("synthetic database outage");
  }

  @Test
  void unregisteredAuthorityIsDeniedWithoutQueryingGrantStorage() {
    AtomicBoolean lookupCalled = new AtomicBoolean();
    AuthorizationGrantLookup lookup =
        (actor, authority, scope) -> {
          lookupCalled.set(true);
          return true;
        };
    AuthorizationService service = new AuthorizationService(lookup, PhaseTwoAuthorities.catalog());

    AuthorizationDecision decision = service.evaluate(context(), "UnregisteredAuthority");

    assertThat(decision.isAllowed()).isFalse();
    assertThat(decision.denialReason())
        .contains(AuthorizationDecision.DenialReason.AUTHORITY_NOT_CONFIGURED);
    assertThat(lookupCalled).isFalse();
  }

  @Test
  void authorityWithUnsupportedScopeKindDeniesBeforeStorageLookup() {
    AtomicBoolean lookupCalled = new AtomicBoolean();
    AuthorizationGrantLookup lookup =
        (actor, authority, scope) -> {
          lookupCalled.set(true);
          return true;
        };
    AuthorizationService service = new AuthorizationService(lookup, PhaseTwoAuthorities.catalog());
    ExecutionContext organizationContext =
        new ExecutionContextFactory((actor, scope) -> true)
            .forInteractiveRequest(
                ACTOR,
                RequestedExecutionScope.organization(
                    new org.ayuvahak.platform.core.ExternalReference(
                        new IssuerId("https://directory.example.test"), "Organization", "org-4")),
                CorrelationId.generate());

    AuthorizationDecision decision =
        service.evaluate(organizationContext, PhaseTwoAuthorities.AUDIT_REVIEW.code());

    assertThat(decision.isAllowed()).isFalse();
    assertThat(decision.denialReason())
        .contains(AuthorizationDecision.DenialReason.SCOPE_NOT_SUPPORTED);
    assertThat(lookupCalled).isFalse();
  }

  private static ExecutionContext context() {
    return new ExecutionContextFactory((actor, scope) -> true)
        .forInteractiveRequest(ACTOR, RequestedExecutionScope.platform(), CorrelationId.generate());
  }
}
