package org.ayuvahak.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.platform.authorization.adapter.persistence.JdbcAuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.authorization.domain.PhaseTwoAuthorities;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.ExecutionScopeValidator;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class AuthorizationDecisionIT {

  private static final String TEST_PASSWORD = "local-test-only";
  private static final IssuerId ISSUER = new IssuerId("https://identity.example.test/realm");
  private static final ActorReference ACTOR = ActorReference.human(ISSUER, "synthetic-user-17");
  private static final ExternalReference ORGANIZATION =
      new ExternalReference(
          new IssuerId("https://directory.example.test"), "Organization", "org-4");
  private static final ExternalReference OTHER_ORGANIZATION =
      new ExternalReference(
          new IssuerId("https://directory.example.test"), "Organization", "org-5");
  private static final ExternalReference FACILITY =
      new ExternalReference(
          new IssuerId("https://directory.example.test"), "Facility", "facility-2");
  private static final ExternalReference OTHER_FACILITY =
      new ExternalReference(
          new IssuerId("https://directory.example.test"), "Facility", "facility-3");
  private static final FunctionalAuthority SCOPE_PROBE =
      new FunctionalAuthority("TestScopeProbe", EnumSet.allOf(ExecutionScope.Kind.class));

  private static final PostgreSQLContainer postgres =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  private static JdbcTemplate jdbcTemplate;
  private static AuthorizationService service;

  @BeforeAll
  static void migrateDisposableDatabase() {
    postgres.start();
    var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    AuthorizationGrantLookup grantLookup = new JdbcAuthorizationGrantLookup(jdbcTemplate);
    var authorities =
        new FunctionalAuthorityCatalog(
            Set.of(
                PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION,
                PhaseTwoAuthorities.AUDIT_REVIEW,
                PhaseTwoAuthorities.EVENT_DELIVERY_OPERATIONS,
                SCOPE_PROBE));
    service = new AuthorizationService(grantLookup, authorities);
  }

  @AfterAll
  static void stopDisposableDatabase() {
    postgres.stop();
  }

  @BeforeEach
  void clearGrantState() {
    jdbcTemplate.update("DELETE FROM identity_access.authorization_grant");
  }

  @Test
  void activePlatformGrantAllowsTheMatchingAuthorityForTheExactActor() {
    insertGrant(
        ACTOR,
        PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION.code(),
        RequestedExecutionScope.platform(),
        "ACTIVE");

    AuthorizationDecision decision =
        service.evaluate(
            context(ACTOR, RequestedExecutionScope.platform()),
            PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION.code());

    assertThat(decision.isAllowed()).isTrue();
  }

  @Test
  void platformGrantDoesNotImplyOrganizationScope() {
    insertGrant(ACTOR, SCOPE_PROBE.code(), RequestedExecutionScope.platform(), "ACTIVE");

    AuthorizationDecision decision =
        service.evaluate(
            context(ACTOR, RequestedExecutionScope.organization(ORGANIZATION)), SCOPE_PROBE.code());

    assertDenied(decision, AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
  }

  @Test
  void organizationGrantDoesNotImplyFacilityScope() {
    insertGrant(
        ACTOR, SCOPE_PROBE.code(), RequestedExecutionScope.organization(ORGANIZATION), "ACTIVE");

    AuthorizationDecision decision =
        service.evaluate(
            context(ACTOR, RequestedExecutionScope.facility(ORGANIZATION, FACILITY)),
            SCOPE_PROBE.code());

    assertDenied(decision, AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
  }

  @Test
  void facilityGrantRequiresTheExactFacilityAndOrganizationReferences() {
    insertGrant(
        ACTOR,
        SCOPE_PROBE.code(),
        RequestedExecutionScope.facility(ORGANIZATION, FACILITY),
        "ACTIVE");

    assertThat(
            service
                .evaluate(
                    context(ACTOR, RequestedExecutionScope.facility(ORGANIZATION, FACILITY)),
                    SCOPE_PROBE.code())
                .isAllowed())
        .isTrue();
    assertDenied(
        service.evaluate(
            context(ACTOR, RequestedExecutionScope.facility(ORGANIZATION, OTHER_FACILITY)),
            SCOPE_PROBE.code()),
        AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
    assertDenied(
        service.evaluate(
            context(ACTOR, RequestedExecutionScope.facility(OTHER_ORGANIZATION, FACILITY)),
            SCOPE_PROBE.code()),
        AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
  }

  @Test
  void organizationGrantAllowsOnlyTheExactOrganization() {
    insertGrant(
        ACTOR, SCOPE_PROBE.code(), RequestedExecutionScope.organization(ORGANIZATION), "ACTIVE");

    assertThat(
            service
                .evaluate(
                    context(ACTOR, RequestedExecutionScope.organization(ORGANIZATION)),
                    SCOPE_PROBE.code())
                .isAllowed())
        .isTrue();
    assertDenied(
        service.evaluate(
            context(ACTOR, RequestedExecutionScope.organization(OTHER_ORGANIZATION)),
            SCOPE_PROBE.code()),
        AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
  }

  @Test
  void inactiveAndRevokedGrantsDoNotAuthorize() {
    insertGrant(ACTOR, SCOPE_PROBE.code(), RequestedExecutionScope.platform(), "INACTIVE");
    assertDenied(
        service.evaluate(context(ACTOR, RequestedExecutionScope.platform()), SCOPE_PROBE.code()),
        AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);

    jdbcTemplate.update("DELETE FROM identity_access.authorization_grant");
    insertGrant(ACTOR, SCOPE_PROBE.code(), RequestedExecutionScope.platform(), "REVOKED");
    assertDenied(
        service.evaluate(context(ACTOR, RequestedExecutionScope.platform()), SCOPE_PROBE.code()),
        AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
  }

  @Test
  void committedRevocationTakesEffectOnTheNextDecision() {
    insertGrant(ACTOR, SCOPE_PROBE.code(), RequestedExecutionScope.platform(), "ACTIVE");
    ExecutionContext context = context(ACTOR, RequestedExecutionScope.platform());
    assertThat(service.evaluate(context, SCOPE_PROBE.code()).isAllowed()).isTrue();

    jdbcTemplate.update(
        "UPDATE identity_access.authorization_grant SET grant_status = 'REVOKED', "
            + "revoked_at = CURRENT_TIMESTAMP WHERE principal_identifier = ?",
        ACTOR.identity().value());

    assertDenied(
        service.evaluate(context, SCOPE_PROBE.code()),
        AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
  }

  @Test
  void actorIdentityAndIssuerArePartOfTheGrantMatch() {
    insertGrant(ACTOR, SCOPE_PROBE.code(), RequestedExecutionScope.platform(), "ACTIVE");
    ActorReference otherActor = ActorReference.human(ISSUER, "synthetic-user-18");
    ActorReference sameSubjectFromOtherIssuer =
        ActorReference.human(
            new IssuerId("https://other-identity.example.test"), "synthetic-user-17");
    ActorReference serviceActor = ActorReference.service(ISSUER, "synthetic-worker");

    assertDenied(
        service.evaluate(
            context(otherActor, RequestedExecutionScope.platform()), SCOPE_PROBE.code()),
        AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
    assertDenied(
        service.evaluate(
            context(sameSubjectFromOtherIssuer, RequestedExecutionScope.platform()),
            SCOPE_PROBE.code()),
        AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
    assertDenied(
        service.evaluate(
            context(serviceActor, RequestedExecutionScope.platform()), SCOPE_PROBE.code()),
        AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
  }

  @Test
  void grantForOneAuthorityDoesNotPermitAnotherAuthority() {
    insertGrant(ACTOR, SCOPE_PROBE.code(), RequestedExecutionScope.platform(), "ACTIVE");

    assertDenied(
        service.evaluate(
            context(ACTOR, RequestedExecutionScope.platform()),
            PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION.code()),
        AuthorizationDecision.DenialReason.NO_ACTIVE_GRANT);
  }

  @Test
  void unknownAuthorityAndUnsupportedScopeDeny() {
    assertDenied(
        service.evaluate(
            context(ACTOR, RequestedExecutionScope.platform()), "UnregisteredAuthority"),
        AuthorizationDecision.DenialReason.AUTHORITY_NOT_CONFIGURED);
    insertGrant(
        ACTOR,
        PhaseTwoAuthorities.AUDIT_REVIEW.code(),
        RequestedExecutionScope.organization(ORGANIZATION),
        "ACTIVE");
    assertDenied(
        service.evaluate(
            context(ACTOR, RequestedExecutionScope.organization(ORGANIZATION)),
            PhaseTwoAuthorities.AUDIT_REVIEW.code()),
        AuthorizationDecision.DenialReason.SCOPE_NOT_SUPPORTED);
  }

  @Test
  void missingExecutionContextDenies() {
    assertDenied(
        service.evaluate(null, PhaseTwoAuthorities.ACCESS_GRANT_ADMINISTRATION.code()),
        AuthorizationDecision.DenialReason.EXECUTION_CONTEXT_REQUIRED);
  }

  private static void assertDenied(
      AuthorizationDecision decision, AuthorizationDecision.DenialReason expectedReason) {
    assertThat(decision.isAllowed()).isFalse();
    assertThat(decision.denialReason()).contains(expectedReason);
  }

  private ExecutionContext context(ActorReference actor, RequestedExecutionScope requestedScope) {
    ExecutionScopeValidator referenceValidator =
        (candidate, scope) -> {
          if (candidate == null || scope == null) {
            return false;
          }
          if (scope instanceof RequestedExecutionScope.Facility facilityScope) {
            return (facilityScope.organization().equals(ORGANIZATION)
                    && (facilityScope.facility().equals(FACILITY)
                        || facilityScope.facility().equals(OTHER_FACILITY)))
                || (facilityScope.organization().equals(OTHER_ORGANIZATION)
                    && facilityScope.facility().equals(FACILITY));
          }
          return true;
        };
    ExecutionContextFactory contextFactory = new ExecutionContextFactory(referenceValidator);
    if (actor.kind() == ActorKind.SERVICE) {
      return contextFactory.forIntegrationRequest(actor, requestedScope, CorrelationId.generate());
    }
    return contextFactory.forInteractiveRequest(actor, requestedScope, CorrelationId.generate());
  }

  private void insertGrant(
      ActorReference actor, String authorityCode, RequestedExecutionScope scope, String state) {
    String scopeKind;
    ExternalReference organization = null;
    ExternalReference facility = null;
    if (scope instanceof RequestedExecutionScope.Platform) {
      scopeKind = "PLATFORM";
    } else if (scope instanceof RequestedExecutionScope.Organization organizationScope) {
      scopeKind = "ORGANIZATION";
      organization = organizationScope.organization();
    } else if (scope instanceof RequestedExecutionScope.Facility facilityScope) {
      scopeKind = "FACILITY";
      organization = facilityScope.organization();
      facility = facilityScope.facility();
    } else {
      throw new IllegalArgumentException("unsupported test scope");
    }

    jdbcTemplate.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, principal_issuer, "
            + "principal_identifier_type, principal_identifier, authority_code, scope_kind, "
            + "organization_issuer, organization_identifier_type, organization_identifier, "
            + "facility_issuer, facility_identifier_type, facility_identifier, "
            + "grant_status, created_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "?, ?, CURRENT_TIMESTAMP, CASE WHEN ? = 'REVOKED' THEN CURRENT_TIMESTAMP ELSE NULL END)",
        UUID.randomUUID(),
        actor.kind().name(),
        actor.identity().issuer().value(),
        actor.identity().resourceType(),
        actor.identity().value(),
        authorityCode,
        scopeKind,
        organization == null ? null : organization.issuer().value(),
        organization == null ? null : organization.resourceType(),
        organization == null ? null : organization.value(),
        facility == null ? null : facility.issuer().value(),
        facility == null ? null : facility.resourceType(),
        facility == null ? null : facility.value(),
        state,
        state);
  }
}
