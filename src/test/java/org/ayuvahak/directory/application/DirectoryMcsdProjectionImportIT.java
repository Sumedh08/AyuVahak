package org.ayuvahak.directory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryConceptType;
import org.ayuvahak.directory.domain.DirectoryLifecycleStatus;
import org.ayuvahak.directory.domain.Wgs84Coordinate;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "ayuvahak.authentication.issuer=https://identity.example.test/realms/ayuvahak",
      "ayuvahak.authentication.jwk-set-uri=https://identity.example.test/realms/ayuvahak/protocol/openid-connect/certs",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-mcsd-worker"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DirectoryMcsdProjectionImportIT {

  private static final String TEST_PASSWORD = "local-synthetic-only";
  private static final String AUTH_ISSUER = "https://identity.example.test/realms/ayuvahak";
  private static final String SERVICE_CLIENT = "synthetic-mcsd-worker";
  private static final String SOURCE_NAMESPACE = "urn:synthetic:mcsd:directory-a";
  private static final String PROFILE = "urn:synthetic:profile:mcsd:organization";
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;

  @Autowired private DirectorySourceProjectionImportPort importer;
  @Autowired private ExecutionContextFactory executionContextFactory;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void localDatabase(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
  }

  @BeforeEach
  void resetSyntheticState() {
    jdbc.execute(
        "DROP TRIGGER IF EXISTS test_fail_mcsd_organization_insert ON directory.organization");
    jdbc.execute("DROP FUNCTION IF EXISTS directory.test_fail_mcsd_organization_insert()");
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_mcsd_location_insert ON directory.location");
    jdbc.execute("DROP FUNCTION IF EXISTS directory.test_fail_mcsd_location_insert()");
    jdbc.update("DELETE FROM directory.mcsd_source_projection");
    jdbc.update("DELETE FROM directory.external_identifier_revision");
    jdbc.update("DELETE FROM directory.external_identifier");
    jdbc.update("DELETE FROM directory.healthcare_service_revision");
    jdbc.update("DELETE FROM directory.healthcare_service");
    jdbc.update("DELETE FROM directory.location_revision");
    jdbc.update("DELETE FROM directory.location");
    jdbc.update("DELETE FROM directory.facility_revision");
    jdbc.update("DELETE FROM directory.facility");
    jdbc.update("DELETE FROM directory.organization_revision");
    jdbc.update("DELETE FROM directory.organization");
    jdbc.update("DELETE FROM audit.audit_record");
    jdbc.update("DELETE FROM identity_access.authorization_grant");
    grantMcsdWorkerDirectoryCuration();
  }

  @AfterEach
  void removeFailureTrigger() {
    jdbc.execute(
        "DROP TRIGGER IF EXISTS test_fail_mcsd_organization_insert ON directory.organization");
    jdbc.execute("DROP FUNCTION IF EXISTS directory.test_fail_mcsd_organization_insert()");
    jdbc.execute("DROP TRIGGER IF EXISTS test_fail_mcsd_location_insert ON directory.location");
    jdbc.execute("DROP FUNCTION IF EXISTS directory.test_fail_mcsd_location_insert()");
  }

  @Test
  void mappedOrganizationAndProjectionLedgerCommitAtomicallyAndRepeatedDeliveryIsIdempotent() {
    DirectorySourceProjection projection = organizationProjection("v1", fingerprint("org-a"));

    var first = importer.importSourceProjection(context("mcsd-import-first"), projection);

    assertThat(first.outcome()).isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CREATE);
    assertThat(first.conceptId()).isPresent();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directory.organization", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM directory.mcsd_source_projection", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM directory.external_identifier WHERE issuer = ? "
                    + "AND resource_type = 'Organization' AND identifier_value = 'org-a' "
                    + "AND lifecycle_status = 'ACTIVE'",
                Integer.class,
                SOURCE_NAMESPACE))
        .isEqualTo(1);

    var replay = importer.importSourceProjection(context("mcsd-import-replay"), projection);

    assertThat(replay.outcome())
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.UNCHANGED);
    assertThat(replay.conceptId()).isEqualTo(first.conceptId());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directory.organization", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT version FROM directory.mcsd_source_projection", Long.class))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'directory' "
                    + "AND table_name = 'mcsd_source_projection' AND column_name IN "
                    + "('payload', 'resource_json', 'raw_fhir')",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code ="
                    + " 'DIRECTORY_MCSD_SOURCE_IMPORT' AND target_type = 'Organization' AND outcome"
                    + " = 'SUCCEEDED'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void failedOwnerInsertLeavesNeitherDirectoryFactNorSourceProjectionClaim() {
    jdbc.execute(
        "CREATE FUNCTION directory.test_fail_mcsd_organization_insert() RETURNS trigger LANGUAGE"
            + " plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic owner write failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER test_fail_mcsd_organization_insert BEFORE INSERT ON directory.organization "
            + "FOR EACH ROW EXECUTE FUNCTION directory.test_fail_mcsd_organization_insert()");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                importer.importSourceProjection(
                    context("mcsd-import-rollback"),
                    organizationProjection("v1", fingerprint("org-a"))))
        .isInstanceOf(RuntimeException.class);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directory.organization", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM directory.mcsd_source_projection", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM directory.external_identifier", Integer.class))
        .isZero();
  }

  @Test
  void failedPrimaryLocationInsertRollsBackFacilityIdentifierLedgerAndAuditTogether() {
    importOrganization();
    jdbc.execute(
        "CREATE FUNCTION directory.test_fail_mcsd_location_insert() RETURNS trigger LANGUAGE"
            + " plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic location write failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER test_fail_mcsd_location_insert BEFORE INSERT ON directory.location "
            + "FOR EACH ROW EXECUTE FUNCTION directory.test_fail_mcsd_location_insert()");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                importer.importSourceProjection(
                    context("mcsd-facility-rollback"),
                    facilityProjection("v1", "A", true, at(1), true)))
        .isInstanceOf(RuntimeException.class);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directory.facility", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM directory.mcsd_source_projection WHERE source_resource_type ="
                    + " 'Location'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM directory.external_identifier WHERE concept_type ="
                    + " 'FACILITY'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code ="
                    + " 'DIRECTORY_MCSD_SOURCE_IMPORT' AND target_type = 'Facility'",
                Integer.class))
        .isZero();
  }

  @Test
  void topLevelLocationImportsFacilityAndItsPrimaryOwnerLocation() {
    importOrganization();
    DirectorySourceProjection.FacilityFact fact = facilityProjection("v1", "A", true, at(1), true);

    var result = importer.importSourceProjection(context("mcsd-facility-create"), fact);

    assertThat(result.outcome()).isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CREATE);
    assertThat(result.conceptType()).contains(DirectoryConceptType.FACILITY);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directory.facility", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directory.location", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM directory.location WHERE is_primary AND name = 'Synthetic"
                    + " Facility A'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT concept_type FROM directory.mcsd_source_projection WHERE"
                    + " source_resource_type = 'Location'",
                String.class))
        .isEqualTo("FACILITY");
  }

  @Test
  void childLocationImportsAgainstItsNamespacedParentFacility() {
    importOrganization();
    importer.importSourceProjection(
        context("mcsd-facility-parent"), facilityProjection("v1", "A", true, at(1), true));
    DirectorySourceProjection.LocationFact fact =
        childLocationProjection("v1", "Entrance", true, at(1));

    var result = importer.importSourceProjection(context("mcsd-location-create"), fact);

    assertThat(result.outcome()).isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CREATE);
    assertThat(result.conceptType()).contains(DirectoryConceptType.LOCATION);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directory.location", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM directory.location WHERE name = 'Synthetic Entrance' AND NOT"
                    + " is_primary",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM directory.mcsd_source_projection WHERE concept_type ="
                    + " 'LOCATION'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void healthcareServiceImportsAndReplayDoNotCreateCapabilityAssertions() {
    importOrganization();
    importer.importSourceProjection(
        context("mcsd-service-facility"), facilityProjection("v1", "A", true, at(1), true));
    DirectorySourceProjection.HealthcareServiceFact fact =
        healthcareServiceProjection("v1", "Synthetic Imaging", true, at(1));

    var first = importer.importSourceProjection(context("mcsd-service-create"), fact);
    var replay = importer.importSourceProjection(context("mcsd-service-replay"), fact);

    assertThat(first.outcome()).isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CREATE);
    assertThat(first.conceptType()).contains(DirectoryConceptType.HEALTHCARE_SERVICE);
    assertThat(replay.outcome())
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.UNCHANGED);
    assertThat(replay.conceptId()).isEqualTo(first.conceptId());
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM directory.healthcare_service", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM directory.facility_capability_assertion", Integer.class))
        .isZero();
  }

  @Test
  void facilityUpdateAndExplicitDeactivationUseOwnerLifecycle() {
    importOrganization();
    var created =
        importer.importSourceProjection(
            context("mcsd-facility-create"), facilityProjection("v1", "A", true, at(1), true));

    var updated =
        importer.importSourceProjection(
            context("mcsd-facility-update"), facilityProjection("v2", "B", true, at(2), true));
    var deactivated =
        importer.importSourceProjection(
            context("mcsd-facility-deactivate"), facilityProjection("v3", "B", false, at(3), true));

    assertThat(updated.outcome()).isEqualTo(DirectorySourceProjectionReconciliation.Outcome.UPDATE);
    assertThat(deactivated.outcome())
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE);
    assertThat(deactivated.conceptId()).isEqualTo(created.conceptId());
    assertThat(jdbc.queryForObject("SELECT name FROM directory.facility", String.class))
        .isEqualTo("Synthetic Facility B");
    assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM directory.facility", String.class))
        .isEqualTo(DirectoryLifecycleStatus.INACTIVE.name());
    assertThat(
            jdbc.queryForObject(
                "SELECT source_active FROM directory.mcsd_source_projection WHERE"
                    + " source_resource_type = 'Location'",
                Boolean.class))
        .isFalse();
  }

  @Test
  void healthcareServiceExplicitDeactivationUsesOwnerLifecycle() {
    importOrganization();
    importer.importSourceProjection(
        context("mcsd-service-facility"), facilityProjection("v1", "A", true, at(1), true));
    var created =
        importer.importSourceProjection(
            context("mcsd-service-create"),
            healthcareServiceProjection("v1", "Synthetic Imaging", true, at(1)));

    var deactivated =
        importer.importSourceProjection(
            context("mcsd-service-deactivate"),
            healthcareServiceProjection("v2", "Synthetic Imaging", false, at(2)));

    assertThat(deactivated.outcome())
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE);
    assertThat(deactivated.conceptId()).isEqualTo(created.conceptId());
    assertThat(
            jdbc.queryForObject(
                "SELECT lifecycle_status FROM directory.healthcare_service", String.class))
        .isEqualTo(DirectoryLifecycleStatus.INACTIVE.name());
  }

  @Test
  void staleAndEqualRevisionConflictsDoNotOverwriteFacility() {
    importOrganization();
    importer.importSourceProjection(
        context("mcsd-facility-create"), facilityProjection("v2", "B", true, at(2), true));

    var stale =
        importer.importSourceProjection(
            context("mcsd-facility-stale"), facilityProjection("v1", "A", true, at(1), true));
    var equalConflict =
        importer.importSourceProjection(
            context("mcsd-facility-equal-conflict"),
            facilityProjection("v2", "A", true, at(2), true));

    assertThat(stale.outcome()).isEqualTo(DirectorySourceProjectionReconciliation.Outcome.STALE);
    assertThat(equalConflict.outcome())
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CONFLICT);
    assertThat(jdbc.queryForObject("SELECT name FROM directory.facility", String.class))
        .isEqualTo("Synthetic Facility B");
  }

  @Test
  void inactiveChildLocationRequiresReconciliationAndPartialAbsenceDoesNotSweepDelete() {
    importOrganization();
    importer.importSourceProjection(
        context("mcsd-facility-parent"), facilityProjection("v1", "A", true, at(1), true));
    var created =
        importer.importSourceProjection(
            context("mcsd-location-create"),
            childLocationProjection("v1", "Entrance", true, at(1)));

    // This per-fact owner command has no source-run/cursor/sweep operation. An omitted fact in a
    // partial source result therefore issues no mutation; child Locations also have no inactive
    // owner lifecycle state in the frozen Directory model.
    var requiresReconciliation =
        importer.importSourceProjection(
            context("mcsd-location-inactive"),
            childLocationProjection("v2", "Entrance", false, at(2)));

    assertThat(requiresReconciliation.outcome())
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED);
    assertThat(requiresReconciliation.conceptId()).isEqualTo(created.conceptId());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directory.location", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT source_active FROM directory.mcsd_source_projection WHERE"
                    + " source_resource_type = 'Location' AND source_reference = 'child-a'",
                Boolean.class))
        .isTrue();
    assertThat(jdbc.queryForObject("SELECT name FROM directory.facility", String.class))
        .isEqualTo("Synthetic Facility A");
  }

  private void grantMcsdWorkerDirectoryCuration() {
    jdbc.update(
        "INSERT INTO identity_access.authorization_grant (grant_id, principal_kind, "
            + "principal_issuer, principal_identifier_type, principal_identifier, authority_code, "
            + "scope_kind, grant_status, created_at) VALUES (gen_random_uuid(), 'SERVICE', ?, "
            + "'client_id', ?, 'DirectoryCuration', 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP)",
        AUTH_ISSUER,
        SERVICE_CLIENT);
  }

  private ExecutionContext context(String correlationId) {
    return executionContextFactory.forIntegrationRequest(
        ActorReference.service(new IssuerId(AUTH_ISSUER), SERVICE_CLIENT),
        RequestedExecutionScope.platform(),
        new CorrelationId(correlationId));
  }

  private static DirectorySourceProjection.OrganizationFact organizationProjection(
      String version, String projectionFingerprint) {
    ExternalReference sourceIdentity =
        new ExternalReference(new IssuerId(SOURCE_NAMESPACE), "Organization", "org-a");
    return new DirectorySourceProjection.OrganizationFact(
        new DirectorySourceProjection.Metadata(
            sourceIdentity,
            Optional.of(version),
            Optional.of(Instant.parse("2026-10-15T00:00:00Z")),
            false,
            PROFILE,
            projectionFingerprint),
        "Synthetic regional health authority",
        Optional.empty(),
        true);
  }

  private void importOrganization() {
    importer.importSourceProjection(
        context("mcsd-organization-parent"), organizationProjection("v1", fingerprint("org-a")));
  }

  private static DirectorySourceProjection.FacilityFact facilityProjection(
      String version, String suffix, boolean active, Instant sourceUpdatedAt, boolean ordered) {
    return new DirectorySourceProjection.FacilityFact(
        metadata(
            "Location",
            "facility-a",
            "urn:synthetic:profile:mcsd:facility",
            version,
            suffix,
            sourceUpdatedAt,
            ordered),
        "Synthetic Facility " + suffix,
        new ExternalReference(new IssuerId(SOURCE_NAMESPACE), "Organization", "org-a"),
        Optional.of(new Wgs84Coordinate(72.8777, 19.0760)),
        active);
  }

  private static DirectorySourceProjection.LocationFact childLocationProjection(
      String version, String label, boolean active, Instant sourceUpdatedAt) {
    return new DirectorySourceProjection.LocationFact(
        metadata(
            "Location",
            "child-a",
            "urn:synthetic:profile:mcsd:child-location",
            version,
            label,
            sourceUpdatedAt,
            true),
        "Synthetic " + label,
        new ExternalReference(new IssuerId(SOURCE_NAMESPACE), "Location", "facility-a"),
        Optional.empty(),
        active);
  }

  private static DirectorySourceProjection.HealthcareServiceFact healthcareServiceProjection(
      String version, String name, boolean active, Instant sourceUpdatedAt) {
    return new DirectorySourceProjection.HealthcareServiceFact(
        metadata(
            "HealthcareService",
            "service-a",
            "urn:synthetic:profile:mcsd:service",
            version,
            name,
            sourceUpdatedAt,
            true),
        name,
        new ExternalReference(new IssuerId(SOURCE_NAMESPACE), "Organization", "org-a"),
        new ExternalReference(new IssuerId(SOURCE_NAMESPACE), "Location", "facility-a"),
        active);
  }

  private static DirectorySourceProjection.Metadata metadata(
      String resourceType,
      String id,
      String profile,
      String version,
      String content,
      Instant sourceUpdatedAt,
      boolean ordered) {
    return new DirectorySourceProjection.Metadata(
        new ExternalReference(new IssuerId(SOURCE_NAMESPACE), resourceType, id),
        Optional.of(version),
        Optional.of(sourceUpdatedAt),
        ordered,
        profile,
        fingerprint(content));
  }

  private static Instant at(int day) {
    return Instant.parse("2026-10-" + String.format("%02d", day) + "T00:00:00Z");
  }

  private static String fingerprint(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 must be available", exception);
    }
  }
}
