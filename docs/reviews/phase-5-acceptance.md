# Phase 5 Acceptance Evidence — Referral

**Result:** PASS. **Verification date:** 2026-10-09. **Scope:** Phase 5 implementation and combined Phase 3–5 integration. All examples and fixtures were synthetic.

## Verification matrix

| Requirement | Verification | Result | Caveat |
| --- | --- | --- | --- |
| Canonical specification and approved ticket graph | Reviewed `docs/specs/phase-5-referral-spec.md`; completed P5-01 through P5-05 in sequence. | PASS — five tickets resolved; no implementation tickets were sent to an external tracker. | A targeted spec clarification keeps `GetTransferBasis` design-only until Phase 7 has a real Transfer request and participant check. |
| Full integrated tests and build | `./mvnw.cmd -B -ntp clean verify`, command-local Java 25. | PASS — 100 Surefire and 148 Failsafe tests; zero failures, errors, or skips; packaging and Spotless passed. | Failsafe used developer-controlled disposable containers. |
| Directory→Capacity→Referral integrated scenario | `DirectoryCapacityReferralScenarioIT` under the full Failsafe suite, also run alone with `./mvnw.cmd -B -ntp -Dit.test=DirectoryCapacityReferralScenarioIT clean verify`. | PASS — created synthetic organization, origin/destination facilities, and CareCapability; recorded capacity evidence, retrieved current evidence, evaluated `READY`; created and submitted a Referral requiring that capability. | The scenario does not invoke Matching or create a Transfer. Test authorization grants use synthetic fixture rows in the disposable test database. |
| Phase 3–5 combined regression | The same clean verification on `phase5-referral` includes platform, Directory, Capacity, Referral, and integrated-scenario suites and all migrations through V18. Phase 3 and Phase 4 frozen acceptance records were cross-checked. | PASS — no cross-context persistence access or failure in the combined suite; prior Phase 3 and Phase 4 records are PASS. | Prior phase records retain their own commit/evidence; this run verifies their integrated source together with Phase 5. |
| Real PostgreSQL/PostGIS, Flyway, JDBC, and transactions | Failsafe integration suite with Testcontainers and pinned `postgis/postgis:18-3.6` image; migrations V1–V18 ran. | PASS — PostgreSQL `18.6`, PostGIS `3.6.4`; Flyway `12.4.0`; JDBC and transaction behavior passed. | No H2/SQLite substitute. Containers were local and disposable. |
| Phase 5 lifecycle and scope | `ReferralDraftCommandsApiIT` within full suite. | PASS — 25 HTTP/PostGIS tests passed for create/edit, submission/revisions, amendment/resolution, cancellation, scoped reads, list privacy, audit failure, idempotency, and scope behavior. | Uses synthetic issuer-scoped references and local identity fixtures. |
| Cancellation event safety | Referral integration and outbox integration tests; V17/V18 migrations; event contract review. | PASS — `referral.cancelled.v1` has a minimal payload and starts as `WAITING_FOR_CONSUMER` without a Transfer handler; no automatic dispatch/replay/promotion. | No Transfer consumer exists in Phase 5 by design. |
| Module and dependency boundaries | ArchUnit in the normal Maven lifecycle; production source/migration and POM diff review against Phase 4 freeze; full `mvnw dependency:tree` searched for prohibited libraries. | PASS — Referral uses the Directory application contract; no direct Directory persistence access; no new dependencies or future business contexts. No HAPI/FHIR, DHIS2 client, ORM/JPA, jOOQ, broker, Redis/search/graph, workflow, AI, or cloud SDK dependency was found. | The search matched `hibernate-validator:9.1.3.Final`, the already selected validation implementation; no Hibernate ORM dependency was present. Deferred context-to-context contracts activate only when the owning future context exists. |
| Database schema ownership and Phase 6/7 absence | Local Compose startup/migrations followed by read-only `psql` schema/table inspection; ArchUnit tests; integration scenario schema assertions. | PASS — schemas are `audit`, `capacity`, `directory`, `identity_access`, `platform_eventing`, `public`, and `referral`, plus PostGIS-owned `tiger` and `topology`; latest migration is V18. No Matching, Transfer, Transport, or Analytics schema exists. | PostGIS creates its own `tiger` and `topology` schemas/tables. |
| Compose configuration and image build | `docker compose config --quiet`; `docker compose up --build --detach`. | PASS — configuration validated; app image built; PostgreSQL healthy; app and Keycloak started. | Initial bind to `127.0.0.1:8080` failed because that host port was unavailable. Retried with ignored local `.env` ports `18080` and `18081`; no tracked configuration changed. |
| Local runtime endpoints | Local GET requests to aggregate health, liveness, readiness, `/api/v1/system-info`, and `/actuator/prometheus`. | PASS — health/readiness/liveness `UP`; system info returned AyuVahak and API major version 1; Prometheus returned HTTP 200 with JVM metrics. | All requests were to loopback. |
| Local Keycloak reference provider | Bounded local GET polling of `http://127.0.0.1:18081/realms/ayuvahak/.well-known/openid-configuration`. | PASS — discovery returned the configured local issuer. | Keycloak first startup needed about one minute; an early request during initialization ended prematurely, then bounded polling succeeded. No external identity provider was used. |
| Logging and privacy | Existing observability integration tests and Compose startup/request logs; source review of log fields and event payload. | PASS — structured operational logs correlate requests; no request-body or sensitive-header logging was enabled; patient references are omitted from list responses, ordinary logs, metrics, traces, and cancellation events. | Smoke traffic carried no patient information. |
| Git and scope | `git remote -v`; review of current branch diff and source/dependency changes. | PASS — local-only branch `phase5-referral`, no configured remote. No Phase 6/7 code, adapters, or schemas. | Local commits and merges only. |

## Review

The canonical spec reviewer found no actionable findings or scope creep. The standards reviewer found no actual standards violations. Three non-blocking maintainability observations are recorded in the [implementation review](phase-5-implementation-review.md); they do not affect correctness, security, or the module boundary. No blocker or major defect remains unresolved.

## Cross-phase 3–5 check

The combined clean verification passed on the integrated Phase 5 branch after Phase 3 Directory and Phase 4 Capacity were already frozen. Phase 3 and Phase 4 acceptance records both report PASS. The current source keeps Directory as the authority for facility and capability facts, Capacity as the owner of operational observations/readiness, and Referral as the owner of its coordination lifecycle and minimum PatientReference. Referral calls Directory through its application boundary and does not read Directory or Capacity tables. No recommendation, Transfer, or Transport behavior is introduced.

The integrated scenario provides runtime evidence for the permitted composition: Directory creates the source facts; Capacity reads its own policy and observation state against a Directory facility reference and returns readiness; Referral validates its origin and required CareCapability through the Directory-owned contract and stores only its own requirement. The test also checks that Capacity has no capability-named column, Directory has no capacity-named column, Referral stores no Capacity dimension, and no future context schema is present.

## Final gate

- Phase 5 tickets P5-01–P5-05: **resolved**.
- Independent spec and standards reviews: **complete**, with no actionable violations.
- Integrated verification: **PASS**.
- Compose smoke checks: **PASS** after using alternate loopback ports.
- Blockers/major defects unresolved: **0**.
- Phase 6 or Phase 7 started: **No**.
- Remote Git configured or used: **No**.
- External healthcare system mutated or used as a test target: **No**.
- Real patient data or production credentials accessed: **No**.
