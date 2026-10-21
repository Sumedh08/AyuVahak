# Phase 1 Acceptance Evidence

**Verification date:** 2026-10-08  
**Integrated branch:** phase-1/engineering-platform  
**Phase 0 base:** f622578dc278da268e301be033e2e9054b62c3c1
**Status:** PASS for the acceptance checks recorded below. The two-axis code review is complete with no unresolved material findings; see the [Phase 1 implementation review](phase-1-implementation-review.md).

This record reports observed commands and outcomes from the integrated local branch. It does not infer a pass from source inspection. All tests used synthetic data and local disposable infrastructure.

## Build, configuration, and tests

| Requirement | Verification method / command | Result | Caveat |
| --- | --- | --- | --- |
| Maven Wrapper works and resolves its pinned Maven distribution | mvnw.cmd -B -ntp clean verify; Docker builder also ran the checked-in wrapper and SHA-256 validation | PASS: Apache Maven 3.10.0 | First use downloads the wrapper distribution and dependencies from Maven Central. |
| Java 25 baseline is enforced | mvnw.cmd -B -ntp clean verify | PASS: Maven Enforcer accepted Temurin 25.0.4.1+1; compiler used release 25 | CI requests Java 25 Temurin; the project does not require a proprietary JDK vendor. |
| Application compiles and packages | mvnw.cmd -B -ntp clean verify | PASS: compile and Spring Boot repackage completed | The packaged version is 0.1.0-SNAPSHOT; the no-snapshot rule applies to dependencies, not the local application version. |
| Dependency versions are release versions | mvnw.cmd -B -ntp clean verify | PASS: RequireReleaseDeps passed | Versioned local Maven plugins are also used; dependency and plugin coordinates are listed in the implementation review. |
| Valid configuration starts the application | docker compose up --build --detach; GET /actuator/health/readiness | PASS: app started and readiness reported UP | Compose used synthetic local values from the ignored .env copied from .env.example. |
| Invalid mandatory configuration fails clearly | DatabaseConfigurationTest.canonicalApplicationRejectsMissingDatabaseSettingsAtStartup | PASS: the canonical AyuVahakApplication context failed with validation errors for URL, username, and password | A local Compose startup separately proves valid runtime configuration. |
| Unit, architecture, HTTP, configuration, and database tests pass | mvnw.cmd -B -ntp clean verify | PASS: 18 Surefire tests and 8 Failsafe tests; 26 total, zero failures, errors, or skips | The Failsafe tests require a running local Docker-compatible container runtime. |

## Architecture and persistence

| Requirement | Verification method / command | Result | Caveat |
| --- | --- | --- | --- |
| Architecture rules run in the normal verification lifecycle | mvnw.cmd -B -ntp clean verify | PASS: all 3 Phase1ArchitectureTest cases ran | Rules inspect compiled production classes. |
| A deliberate boundary violation is detected | Phase1ArchitectureTest.domainBoundaryRuleDetectsSpringDependencyInTestFixture | PASS: test-only Spring-dependent domain fixture was reported by the ArchUnit rule | The fixture is under test sources and is not a production domain package. |
| No future healthcare context is scaffolded | ArchUnit production-package assertion; source/package scan; migration inspection | PASS: production packages contain platform technical concerns only; no business package or business schema exists | Future context rules activate when their real owning contexts are introduced. |
| PostgreSQL/PostGIS integration uses real disposable infrastructure | PostgresPostgisFoundationIT with Testcontainers and manifest-pinned postgis/postgis:18-3.6 | PASS: local PostgreSQL 18.6 / PostGIS 3.6.4 | No H2, SQLite, public database, or healthcare service was used. |
| Flyway migration infrastructure runs | PostgresPostgisFoundationIT.appliesOnlyThePlatformMigrationAndRecordsItsHistory; Compose startup logs | PASS: V1 was validated/applied and one successful history row was present | The only AyuVahak migration enables the already-installed PostGIS extension. |
| PostGIS query succeeds | PostgresPostgisFoundationIT.executesPostgisAndSpringJdbcQueriesAgainstTheRealDatabase; local Compose query SELECT PostGIS_Full_Version() | PASS: query identified PostGIS 3.6.4 | No healthcare geography behavior was implemented. |
| Spring JDBC executes a real query and transaction rolls back | PostgresPostgisFoundationIT.executesPostgisAndSpringJdbcQueriesAgainstTheRealDatabase and .rollsBackJdbcWorkThroughSpringTransactionInfrastructure | PASS: SELECT 6 * 7 returned 42; rolled-back transaction was reported aborted by PostgreSQL | No business repository or entity was created. |
| Migration history and absence of business tables are verified | PostgresPostgisFoundationIT.appliesOnlyThePlatformMigrationAndRecordsItsHistory | PASS: one successful migration; no AyuVahak business table | PostgreSQL and extension-owned tables are excluded from the business-table assertion. |

## HTTP and observability

| Requirement | Verification method / command | Result | Caveat |
| --- | --- | --- | --- |
| Versioned platform API responds | PlatformHttpIntegrationTest.exposesOnlyNonSensitivePlatformInformationWithGeneratedCorrelation; GET /api/v1/system-info in Compose | PASS: response contains only service name, API major version, and application version | This is the only production application route; it is not a healthcare resource. |
| Malformed and invalid requests use RFC 9457 Problem Details | PlatformHttpIntegrationTest malformed-request and validation cases | PASS: response uses application/problem+json and safe fields | Test-only endpoints exercise generic validation and error handling. |
| Stable platform error code is present | PlatformHttpIntegrationTest error assertions | PASS: platform error codes are asserted | No healthcare business error codes are defined. |
| Correlation is generated, validated, returned, logged, and cleared | PlatformHttpIntegrationTest missing, valid, malformed, duplicate, and log-injection-like header cases; PostgresPostgisFoundationIT trace/correlation assertion | PASS: correlation ID appears in response and request log, and MDC is cleared | Correlation ID remains distinct from trace and future domain correlation/causation IDs. |
| Health, liveness, and readiness are available | PostgresPostgisFoundationIT health-probe assertions; Compose HTTP smoke checks | PASS: all returned UP with database healthy | Health component details are hidden. |
| Database failure does not change process liveness and does make readiness fail | Integrated Compose check: stop database; GET readiness and liveness; restart database; repeat readiness | PASS: readiness returned HTTP 503 after the observed 30-second JDBC acquisition timeout; liveness returned UP; readiness returned UP after restart | During hard database loss, the readiness response can take up to the observed connection-acquisition timeout. The Compose stack uses a local named volume and was removed after the check. |
| Metrics path works and exposes platform signals | PostgresPostgisFoundationIT metric assertions; GET /actuator/prometheus in Compose | PASS: HTTP 200; JVM, HTTP server, and Hikari pool metrics were present | Metrics are scrape-only; no telemetry backend is included. |
| Logs are structured and ordinary request logs omit request bodies and sensitive data | PlatformHttpIntegrationTest captured-log privacy assertion | PASS: synthetic body, query value, Authorization header, and cookie probes were absent; route, status, duration, and correlation were present | The assertion covers the standard request log filter, not every future application log message. |
| Trace-compatible instrumentation works without a collector | PostgresPostgisFoundationIT structured trace/span/correlation assertion; startup with no collector | PASS: trace and span IDs appeared in structured request logs; startup required no collector | OTLP trace export is disabled by default; OTLP metrics push is explicitly disabled. |
| Actuator exposure is restricted | PostgresPostgisFoundationIT endpoint allowlist assertions | PASS: only health and Prometheus HTTP endpoints were exposed; env, loggers, beans, metrics, and shutdown were unavailable | Management routes share the app listener in Phase 1; later deployments must restrict access to trusted operator networks. |

## Local deployment, CI, and scope checks

| Requirement | Verification method / command | Result | Caveat |
| --- | --- | --- | --- |
| Compose configuration validates | docker compose config --quiet | PASS | Run with the ignored local .env created from .env.example. |
| Local database starts and application connects | docker compose up --build --detach; docker compose ps; GET readiness | PASS: database healthy, app running, database port unpublished, app port loopback-bound | Host port 8080 was unavailable under local Windows socket policy; the integrated smoke used ignored .env with AYUVAHAK_HTTP_PORT=18080. The checked-in example defaults to 8080. |
| Documented startup path works | Contributor-guide commands: copy .env.example to .env; docker compose config --quiet; docker compose up --build --detach | PASS: Docker image built through the wrapper; app, PostGIS, Flyway, readiness, liveness, system-info, and Prometheus checks succeeded | The tested app ran as UID/GID 10001 with UTC timezone. |
| Local verification matches the substantive CI gates | mvnw.cmd -B -ntp clean verify; .github/workflows/ci.yml inspection and local PyYAML assertions | PASS: CI and contributor guide use clean verify; CI grants contents: read only and uses a Docker-capable runner | actionlint is unavailable, and the workflow was not run on GitHub because no remote is configured or used. |
| Forbidden dependencies and production implementations are absent | mvnw.cmd -B -ntp dependency:tree; focused scan of src/main/java and src/main/resources; Enforcer during clean verify | PASS: no FHIR, DHIS2, Spring Security, Keycloak, Kafka, Redis, search, ORM/JPA, jOOQ, AI/LLM, outbox, idempotency, or business healthcare implementation was found | A broad Hibernate token matched Hibernate Validator; inspection confirmed it is Jakarta Bean Validation, not Hibernate ORM. |
| No external healthcare service is a test target | Test source and test configuration scan; local Testcontainers and Compose runs | PASS: integration checks used only local disposable PostGIS and synthetic fixtures | Maven Central and container image downloads were read-only dependency/image retrievals. |
| Markdown links in new implementation and acceptance artifacts resolve | Local relative-link check for README, contributor, observability, database, HTTP, ticket, and review documents | PASS | External links were not used as runtime dependencies. |
| Local deployment state is cleaned after acceptance | docker compose down --volumes --remove-orphans; Test-Path .env | PASS: app/database containers, network, database volume, and ignored .env were removed | Docker base images remain cached locally; no image was published. |

## Safety and scope

No authentication, authorization, organization execution context, audit, provenance framework, domain events, transactional outbox, idempotency, duplicate-command protection, healthcare bounded context, FHIR/DHIS2 connector, AI/LLM feature, Kafka, or Kubernetes implementation was added. No production database tables or future business schemas were created.

Git activity was local only. The Phase 0 baseline is preserved on local main and Phase 1 work is on a local branch. The remote list was empty. No remote Git operation, issue, pull request, release, workflow run, package publication, or container publication occurred. No external healthcare system was mutated or used as a test target. No account or credential was used or created, and no real patient data was accessed.

