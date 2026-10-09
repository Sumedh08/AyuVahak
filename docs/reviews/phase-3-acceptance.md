# Phase 3 acceptance evidence

**Verification date:** 2026-10-09
**Environment:** Windows; Eclipse Temurin Java `25.0.4.1+1`; Docker Engine `29.3.1`; Docker Compose `v5.1.1`; synthetic test data only.
**Result:** PASS — Phase 3 implementation gates passed locally.

## Verification results

| Requirement | Verification | Result | Caveat |
| --- | --- | --- | --- |
| Clean build, unit tests, architecture tests, integration tests, formatting and packaging | Set `JAVA_HOME` for the command to the installed Temurin 25 JDK; run `mvnw.cmd -B -ntp clean verify`. | PASS — Surefire: 94 tests; Failsafe: 86 tests; zero failures, errors, or skips. Build completed successfully. | Testcontainers used only disposable local containers. |
| Directory behavior | Included in the full clean verification; `DirectoryOrganizationFacilityApiIT`. | PASS — 28 integration tests; zero failures or errors. Coverage includes external identifiers, correction history, uniqueness, concurrency, idempotency, scoped authorization, paginated search, PostGIS queries, and audit rollback. | All records were synthetic. Test setup's Identity & Access grant events are distinguished from Directory behavior in event-count assertions. |
| PostgreSQL/PostGIS, Flyway, JDBC and transaction behavior | Full Failsafe suite, including `PostgresPostgisFoundationIT` and Directory integration tests, against the pinned disposable PostGIS image. | PASS — real PostgreSQL/PostGIS query, Flyway migrations, Spring JDBC operations, and transaction behavior passed. | No in-memory database substitute was used. |
| Dependency and production-scope guard | Resolved dependency-tree/source review and architecture/review passes. | PASS — no prohibited FHIR/DHIS2, broker, ORM/JPA, Redis/search/graph, AI, or future business-context dependency/schema was introduced. | Platform and Directory-owned schema state is expected. |
| Compose configuration and local startup | `docker compose config --quiet`; `docker compose down`; `docker compose up --build --detach`. | PASS — configuration parsed, app image built, database became healthy, and application started after a stopped-state restart. | The existing local database volume was retained. The ignored local `.env` selects port `18080` because this host cannot bind `127.0.0.1:8080`. |
| Application readiness, liveness and platform API | Local GET requests to `/actuator/health/readiness`, `/actuator/health/liveness`, and `/api/v1/system-info` on `127.0.0.1:18080`. | PASS — all returned HTTP 200; readiness/liveness were `UP`; system info reported `AyuVahak` and API major version `1`. | System info is intentionally non-sensitive and does not test protected Directory behavior. |
| Metrics and local identity-provider discovery | Local GET `/actuator/prometheus`; bounded polling of the local Keycloak discovery URL. | PASS — Prometheus returned HTTP 200 with JVM metrics; discovery returned issuer `http://localhost:8081/realms/ayuvahak`. | Keycloak first-start initialization took about one minute; an immediate request during startup ended early, and discovery succeeded after bounded polling. |
| Local-only Git and external-system safety | `git remote -v`; review of commands and test targets. | PASS — no configured remote and no remote Git operation. All test infrastructure was local. | No public healthcare server or public API was used as a test target. |

## Review and scope gate

The final Standards and Spec reviews and defect-first source review found no unresolved BLOCKER or MAJOR issue. Query allowlists, `page`/`size` pagination, bounds, stable ordering, inactive-owner identifier cleanup, and required-capability filter validation match the Phase 3 specification. The latest clean verification ran after those code and test changes.

Phase 3 remains within Directory ownership and the approved Identity & Access grant-administration boundary. No Capacity, Referral, Matching, Transfer, Transport, FHIR, DHIS2, or other future business module/schema was added.

## Freeze decision

**PASS.** Local Phase 3 integration and freeze may proceed. Phase 4 must not start until the local freeze commit is complete.

No external healthcare system was mutated. No public FHIR or DHIS2 server was written to. No remote Git operation, issue, pull request, release, package publication, or container publication occurred. No external credentials or real patient data were used.
