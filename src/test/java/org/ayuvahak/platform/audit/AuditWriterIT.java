package org.ayuvahak.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.audit.application.AuditPersistenceUnavailableException;
import org.ayuvahak.platform.audit.application.AuditWriter;
import org.ayuvahak.platform.audit.domain.AuditActionKind;
import org.ayuvahak.platform.audit.domain.AuditAuthenticationEvidence;
import org.ayuvahak.platform.audit.domain.AuditAuthorizationOutcome;
import org.ayuvahak.platform.audit.domain.AuditEvidence;
import org.ayuvahak.platform.audit.domain.AuditOutcome;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {
      "ayuvahak.authentication.issuer=https://identity.example.test/realms/test",
      "ayuvahak.authentication.jwk-set-uri=http://127.0.0.1:9/jwks",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-worker",
      "ayuvahak.authentication.allow-http=true"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuditWriterIT {

  private static final String LOCAL_TEST_PASSWORD = "local-test-only";
  private static final IssuerId ISSUER = new IssuerId("https://identity.example.test/realms/test");
  private static final ActorReference ACTOR = ActorReference.human(ISSUER, "synthetic-operator-17");
  private static final CorrelationId CORRELATION_ID = new CorrelationId("audit-test-correlation");
  private static final ExecutionScope PLATFORM_SCOPE =
      new ExecutionContextFactory((actor, requestedScope) -> true)
          .forInteractiveRequest(ACTOR, RequestedExecutionScope.platform(), CORRELATION_ID)
          .scope();

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(LOCAL_TEST_PASSWORD);

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired private AuditWriter auditWriter;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private Flyway flyway;

  @Autowired private MockMvc mockMvc;

  @BeforeEach
  void prepareTestFixture() {
    jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS audit_test");
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS audit_test.protected_state (id uuid PRIMARY KEY)");
    jdbcTemplate.execute("TRUNCATE audit_test.protected_state, audit.audit_record");
    jdbcTemplate.execute(
        "CREATE OR REPLACE FUNCTION audit_test.reject_audit_insert() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code = 'TEST_FORCE_FAILURE' THEN "
            + "RAISE EXCEPTION 'synthetic audit persistence failure'; END IF; "
            + "RETURN NEW; END $$");
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_audit_insert ON audit.audit_record");
    jdbcTemplate.execute(
        "CREATE TRIGGER reject_audit_insert BEFORE INSERT ON audit.audit_record "
            + "FOR EACH ROW EXECUTE FUNCTION audit_test.reject_audit_insert()");
  }

  @Test
  void appliesTheAuditOwnerMigrationAtTheReservedVersion() {
    assertThat(flyway.info().applied())
        .extracting(migration -> migration.getVersion().getVersion())
        .contains("3");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT to_regclass('audit.audit_record')::text", String.class))
        .isEqualTo("audit.audit_record");
  }

  @Test
  void mutationEvidenceCommitsWithTheOwnerProvidedTransaction() {
    InternalId protectedStateId = InternalId.generate();
    AuditEvidence evidence = evidence(AuditActionKind.PROTECTED_MUTATION, "GRANT_CREATED");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    InternalId auditId =
        transaction.execute(
            status -> {
              jdbcTemplate.update(
                  "INSERT INTO audit_test.protected_state (id) VALUES (?)",
                  protectedStateId.value());
              return auditWriter.recordMutation(evidence);
            });

    assertThat(auditId).isNotNull();
    assertThat(count("audit_test.protected_state", protectedStateId)).isEqualTo(1);
    assertThat(count("audit.audit_record", auditId)).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT actor_kind, actor_reference, action_code, target_type, target_id, "
                    + "outcome, reason_code, authentication_evidence, authorization_outcome, "
                    + "correlation_id, recorded_at FROM audit.audit_record WHERE id = ?",
                auditId.value()))
        .containsEntry("actor_kind", "HUMAN")
        .containsEntry("actor_reference", "synthetic-operator-17")
        .containsEntry("action_code", "GRANT_CREATED")
        .containsEntry("target_type", "AccessGrant")
        .containsEntry("target_id", evidence.targetId().orElseThrow().value())
        .containsEntry("outcome", "SUCCEEDED")
        .containsEntry("reason_code", "ACCESS_SETUP")
        .containsEntry("authentication_evidence", "HUMAN_ACCESS_TOKEN")
        .containsEntry("authorization_outcome", "ALLOWED")
        .containsEntry("correlation_id", CORRELATION_ID.value())
        .containsKey("recorded_at");
    assertAuditSchemaHasNoPayloadColumns();
  }

  @Test
  void auditInsertFailureRollsBackTheOwnerMutation() {
    InternalId protectedStateId = InternalId.generate();
    AuditEvidence evidence = evidence(AuditActionKind.PROTECTED_MUTATION, "TEST_FORCE_FAILURE");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> {
                      jdbcTemplate.update(
                          "INSERT INTO audit_test.protected_state (id) VALUES (?)",
                          protectedStateId.value());
                      auditWriter.recordMutation(evidence);
                      return null;
                    }))
        .isInstanceOf(AuditPersistenceUnavailableException.class)
        .hasMessage("Audit evidence could not be persisted");

    assertThat(count("audit_test.protected_state", protectedStateId)).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_record WHERE action_code = 'TEST_FORCE_FAILURE'",
                Integer.class))
        .isZero();
  }

  @Test
  void nonMutatingEvidenceCommitsSeparatelyBeforeTheCallerCanDisclose() {
    InternalId protectedStateId = InternalId.generate();
    AuditEvidence evidence = evidence(AuditActionKind.SENSITIVE_READ, "SENSITIVE_RECORD_READ");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    AtomicReference<InternalId> auditId = new AtomicReference<>();

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> {
                      jdbcTemplate.update(
                          "INSERT INTO audit_test.protected_state (id) VALUES (?)",
                          protectedStateId.value());
                      auditId.set(auditWriter.recordNonMutating(evidence));
                      throw new RollbackProbeException();
                    }))
        .isInstanceOf(RollbackProbeException.class);

    assertThat(count("audit_test.protected_state", protectedStateId)).isZero();
    assertThat(count("audit.audit_record", auditId.get())).isEqualTo(1);
  }

  @Test
  void unavailableNonMutatingAuditPathFailsBeforeDisclosure() {
    AuditEvidence evidence = evidence(AuditActionKind.SECURITY_OUTCOME, "TEST_FORCE_FAILURE");
    AtomicBoolean protectedResultDisclosed = new AtomicBoolean();

    assertThatThrownBy(
            () -> {
              auditWriter.recordNonMutating(evidence);
              protectedResultDisclosed.set(true);
            })
        .isInstanceOf(AuditPersistenceUnavailableException.class)
        .hasMessage("Audit evidence could not be persisted");

    assertThat(protectedResultDisclosed).isFalse();
  }

  @Test
  void unauthenticatedProtectedRequestDurablyRecordsMinimizedSecurityEvidence() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/platform/access-grants")
                .header("X-Correlation-ID", "unauthenticated-audit-test"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT action_kind, actor_kind, action_code, target_type, outcome, reason_code, "
                    + "authentication_evidence, authorization_outcome, correlation_id "
                    + "FROM audit.audit_record WHERE correlation_id = ?",
                "unauthenticated-audit-test"))
        .containsEntry("action_kind", "SECURITY_OUTCOME")
        .containsEntry("actor_kind", null)
        .containsEntry("action_code", "AUTHENTICATION_FAILURE")
        .containsEntry("target_type", "ApiRequest")
        .containsEntry("outcome", "DENIED")
        .containsEntry("reason_code", "MISSING_CREDENTIAL")
        .containsEntry("authentication_evidence", "NONE")
        .containsEntry("authorization_outcome", "NOT_EVALUATED")
        .containsEntry("correlation_id", "unauthenticated-audit-test");
  }

  @Test
  void authenticationDenialReturnsAuditUnavailableWhenDurableCaptureFails() throws Exception {
    jdbcTemplate.execute(
        "CREATE OR REPLACE FUNCTION audit_test.reject_audit_insert() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.action_code IN ('TEST_FORCE_FAILURE', 'AUTHENTICATION_FAILURE') THEN "
            + "RAISE EXCEPTION 'synthetic audit persistence failure'; END IF; "
            + "RETURN NEW; END $$");

    mockMvc
        .perform(get("/api/v1/platform/access-grants"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
  }

  @Test
  void mutationCaptureRequiresAnAlreadyActiveOwnerTransaction() {
    assertThatThrownBy(
            () ->
                auditWriter.recordMutation(
                    evidence(AuditActionKind.PROTECTED_MUTATION, "GRANT_CREATED")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Mutation audit requires the protected operation's active transaction");

    assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit.audit_record", Integer.class))
        .isZero();
  }

  private AuditEvidence evidence(AuditActionKind actionKind, String actionCode) {
    return new AuditEvidence(
        actionKind,
        Optional.of(ACTOR),
        Optional.of(PLATFORM_SCOPE),
        actionCode,
        "AccessGrant",
        Optional.of(InternalId.generate()),
        Instant.parse("2026-10-08T10:00:00Z"),
        actionKind == AuditActionKind.SECURITY_OUTCOME
            ? AuditOutcome.DENIED
            : AuditOutcome.SUCCEEDED,
        Optional.of("ACCESS_SETUP"),
        AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN,
        actionKind == AuditActionKind.SECURITY_OUTCOME
            ? AuditAuthorizationOutcome.DENIED
            : AuditAuthorizationOutcome.ALLOWED,
        CORRELATION_ID);
  }

  private int count(String table, InternalId id) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id.value());
  }

  private void assertAuditSchemaHasNoPayloadColumns() {
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'audit' AND table_name = 'audit_record'",
                String.class))
        .noneMatch(
            column ->
                column.contains("payload")
                    || column.contains("token")
                    || column.contains("credential")
                    || column.contains("clinical"));
  }

  private static final class RollbackProbeException extends RuntimeException {}
}
