# Volume II — Integrated Acceptance and Freeze Record

**Result:** PASS — integrated Volume II acceptance passed on 2026-10-15. Phases 3–7 each have their own frozen acceptance evidence. This record is the cross-phase closeout evidence; its local freeze commit is recorded in the documentation-only follow-up commit after that freeze.

## Scope and evidence boundary

This closeout verifies the integrated core care-coordination platform across the frozen Volume I platform and Volume II contexts: Directory, Capacity, Referral, Matching, and Transfer. It does not authorize or implement Volume III. Evidence is from local Maven/Testcontainers runs, disposable local PostgreSQL/PostGIS, and a fresh local Docker Compose stack with local Keycloak. All identifiers and workflow data were synthetic.

## Starting baseline and phase freeze records

- Branch at closeout start: `codex/phase-7-transfer`.
- HEAD at closeout start: `f1733f3f6a41405f572b524afe1a8a16913f7a22`.
- Working-tree state at closeout start: dirty with local Phase 7 closeout changes; those changes were reviewed, verified, and included in the local Volume II freeze commit.
- Git remotes: none configured (`git remote -v` returned no entries).
- Local working tree had the Phase 7 closeout changes listed in the final freeze commit; no remote state was involved.

| Phase | Acceptance evidence | Freeze commit |
|---|---|---|
| 3 — Care Service Directory | [Phase 3 acceptance](phase-3-acceptance.md): PASS / FROZEN | `ede8c470c3c5966ada350ad0df4020f2f0505afc` |
| 4 — Capacity & Operational Readiness | [Phase 4 acceptance](phase-4-acceptance.md): PASS / FROZEN | `39f7edcb1286217a3e28e57e695478e6fa56fc99` |
| 5 — Referral | [Phase 5 acceptance](phase-5-acceptance.md): PASS / FROZEN | `70b9c6d70106109d57b6de17722e8ecdf0b92f98` |
| 6 — Destination Matching & Recommendation | [Phase 6 acceptance](phase-6-acceptance.md): PASS / FROZEN | `f0d92474c67d07dd969b817f429a75b6845c545f` |
| 7 — Transfer Coordination | [Phase 7 acceptance](phase-7-acceptance.md): PASS / FROZEN | `1ed104a6364ccc455876b839c99846cae1a34b24` |

All five acceptance records were present and individually report PASS/FROZEN. The historic Phase 7 record's 139 Surefire / 222 Failsafe counts describe its original gate; the integrated closeout run below is a distinct run after closeout remediation.

## Integrated product scenarios

### Happy path — PASS

The Testcontainers-backed `DirectoryCapacityReferralScenarioIT#volumeTwoHappyPathRunsThroughDirectoryCapacityReferralMatchingAndTransfer` exercised synthetic origin and destination facilities with required ICU, CT, and Neuro capabilities. Facility A lacked a mandatory capability and was ineligible; Facility B had valid capability/location and current READY evidence; Facility C had stale/unknown evidence and remained indeterminate. Matching ranked B first and returned structured evidence without the synthetic PatientReference.

The scenario submitted the Referral, synchronously revalidated applicability before creating the Transfer Journey/request, recorded separate Clinical Suitability and Operational Readiness Assessments and Acceptance, reserved through Capacity, and advanced through READY, departure/IN_TRANSIT, Arrival, Handover, and Completion. Dispatching the approved completion event resolved the Referral idempotently. The test asserts the owner-side persisted outcome.

### Failure and rerouting — PASS

The same integration scenario exercised a separate synthetic journey. Facility B rejected the first request. A refreshed recommendation selected Facility C; a competing Capacity hold caused the reservation attempt to fail. The accepted-but-unreserved state remained truthful and was not reported as READY. The attempt was explicitly closed, a fresh recommendation selected Facility D, and D accepted, reserved, and completed the journey. The prior rejected and accepted attempts remain in history; the prior Acceptance was not erased. Only one active attempt existed at a time, and recommendation outputs excluded PatientReference.

## Recommendation applicability and staleness

Transfer synchronously asks Matching to revalidate the selected recommendation before a request is created; stored `current` status alone is not treated as sufficient. Integrated tests exercised expiration, changed policy version, loss/revocation of Matching's owner-read grant, Capacity changing to not-ready, and withdrawal/unverification of a required capability assertion. These cases returned non-actionable outcomes or failed closed. The tests also verify the recommendation evidence does not contain PatientReference.

The approved applicability contract additionally checks the Referral revision, Referral state, Directory facility state, required capability assertions, capacity freshness/readiness, and policy age/version. The closeout evidence does not claim a separate end-to-end mutation variant for every one of those inputs; the specific variants above are the directly exercised stale-input cases in the integrated scenario and revalidation suite.

## Capacity concurrency and recovery

- **Last-unit race — PASS:** PostgreSQL-backed concurrent reservation requests contend for one available coordination unit. Exactly one reservation succeeds and the other receives the canonical conflict/unavailable outcome. The database is checked after the race; same-key replay returns the recorded result.
- **Lost-response recovery — PASS:** Capacity commits a reservation while the Transfer-side response is lost. Retrying with the same operation identity and a fresh recovery instance reads the Capacity-owned outcome; no duplicate hold is created. Cancellation waits for an unresolved reservation operation to reconcile.
- **Expiry, at-risk and recovered outcomes — PASS:** Capacity-owned events reconcile by reservation sequence. Recovery records the owner outcome without regressing Arrival; a Journey in a coordination exception requires receiving-side reconfirmation before it resumes READY.

## Reality ahead of digital workflow

PostgreSQL integration tests report Arrival before digital reservation/READY or departure is reconciled. They retain the actual `occurredAt`, server `recordedAt`, and attribution, record a coordination exception where required, and do not fabricate a reservation, READY, or departure. Later Capacity recovery restores the owner outcome without rewinding Transfer progress. Separate cases cover late Arrival after interruption and delayed recovery after Arrival.

## Cancellation and lifecycle behavior

The Phase 7 integration suite covers pending-request withdrawal, accepted-but-unreserved closure, reserved pre-departure release, pending/unknown Capacity release and restart reconciliation, cancellation/reservation contention, and post-departure interruption/Arrival behavior. In `TransferJourneyCommandsApiIT#transferProgressKeepsArrivalHandoverAndCompletionDistinctAndPublishesOnce`, attempts to cancel after `IN_TRANSIT`, `ARRIVED`, and `COMPLETED` each return `409 TRANSFER_STATE_CONFLICT`; the scenario then verifies the four original progress facts and terminal Completion remain unchanged. Actual movement is not erased by cancellation.

## Events, outbox, and replay

The full suite verifies owner state and outbox persistence atomically, correlation/causation metadata, at-least-once delivery, consumer deduplication, restart recovery, bounded retries, and per-aggregate ordering where required. It makes no exactly-once claim and no global ordering promise.

| Event | Owner / consumer path | Closeout result |
|---|---|---|
| `referral.cancelled.v1` | Referral → Transfer | PASS. The Phase 2 historical waiting-event fixture remains `WAITING_FOR_CONSUMER`; current cancellation reaches the registered Transfer consumer. No mass replay of historical events was performed. |
| `capacity.reservation-expired.v1` | Capacity → Transfer | PASS; owner outcome reconciled idempotently. |
| `capacity.reservation-at-risk.v1` | Capacity → Transfer | PASS; status is reconciled without inventing release or readiness. |
| `capacity.reservation-recovered.v1` | Capacity → Transfer | PASS; same owner operation outcome is recovered and later Journey progress is preserved. |
| `transfer.completed.v1` | Transfer → Referral | PASS; Referral resolution is idempotent. |
| `transfer.unfulfilled.v1` | Transfer → Referral | PASS; owner event path and consumer handling are covered by the event suite. |

Payload/privacy assertions exclude PatientReference. Tests cover duplicate and delayed delivery, consumer deduplication, restart/recovery, and ordering for aggregates that require causal order.

## Privacy and clinical-data boundary

A distinctive synthetic PatientReference was searched in production source, migration definitions, recommendation snapshots, Transfer storage/views, outbox payloads, audit, and ordinary telemetry assertions. PatientReference is limited to Referral's explicitly authorized origin-side storage and views. Matching, Capacity, and Transfer do not persist it; recommendation evidence, Transfer/outbox data, generic audit, ordinary logs, metrics, and traces do not carry it. List views omit it. Request/response bodies, authorization headers, cookies, and JWTs are not logged by the normal request logging path.

The clinical-data creep source/schema search found no generic clinical note store, diagnosis or medication model, patient chart, arbitrary clinical payload blob, attachment store, or longitudinal patient model. Referral retains coordination-focused minimum context only.

## Context ownership and architecture enforcement

The source import/repository audit found no cross-context imports of private `adapter.persistence` packages. Matching accesses Directory, Capacity, and Referral through their published owner contracts; Transfer uses Matching and Capacity application contracts/events; Referral does not read Directory persistence; Capacity does not read Directory persistence. There are no cross-schema foreign keys. All 11 `Phase1ArchitectureTest` rules ran in the normal `verify` lifecycle. Production package roots are limited to platform and the five Volume II contexts; no placeholder healthcare contexts were scaffolded.

## Database and migrations

Fresh Testcontainers and fresh Compose databases reported PostgreSQL 18.6 and PostGIS 3.6.4. The integration suite executed a PostGIS function/query, Flyway migrations, Spring JDBC operations, and transaction rollback checks against disposable real PostgreSQL/PostGIS. The full closeout run applied 37 Flyway migrations.

Observed non-system schemas were `audit`, `capacity`, `directory`, `identity_access`, `matching`, `platform_eventing`, `referral`, and `transfer`. PostGIS-owned/system schemas included `public`, `tiger`, and `topology`. No `transport`, `fhir`, `dhis2`, `analytics`, patient-warehouse, or unjustified notification business schema was present. Cross-schema foreign-key count was zero.

## Dependency audit

The full Maven dependency tree was generated locally and scanned. Material selected versions included Java 25.0.4.1+1 (Eclipse Temurin), Spring Boot 4.1.1, Maven Wrapper distribution 3.10.0, Flyway 12.4.0, PostgreSQL JDBC 42.7.13, Testcontainers 2.0.5, ArchUnit 1.5.1, Micrometer 1.17.1, OpenTelemetry 1.62.0, and Spotless 3.10.1. Testcontainers used the pinned `postgis/postgis:18-3.6` image digest recorded by the build output; local Compose used the repository's pinned PostgreSQL/PostGIS and Keycloak 26.8.0 images.

No unintended HAPI/FHIR or FHIR model library, DHIS2 SDK/client, OpenHIM/OpenHIE/OpenLMIS client, Hibernate ORM/JPA, jOOQ, Kafka/RabbitMQ/NATS, Redis, Elasticsearch/OpenSearch, Neo4j, MongoDB, Drools, Camunda/Temporal/workflow engine, AI/LLM/embedding framework, external routing SDK, or cloud-provider SDK was found. `hibernate-validator` is present as validation infrastructure and is not Hibernate ORM.

## Security, audit, and idempotency regression

- Local Keycloak service-token tests and authorization suites pass. Signed tokens are validated against configured local issuer/audience/key material; IdP roles are not authorization truth. AyuVahak-owned grants remain authoritative.
- Human and machine identities remain distinct. Execution organization and optional facility context are explicit and server validated. The dedicated Matching service identity has owner-read grants only at explicit deployment scopes and does not inherit the human requester's access.
- Receiving-side Transfer assessments and Acceptance use functional authorities, not hard-coded job titles. Cross-organization access requires a valid scoped grant.
- Protected mutation and required audit evidence commit atomically. Denials and sensitive reads use durable audit behavior and fail closed when required audit persistence fails. Injected audit-store failure is covered. Generic audit excludes PatientReference.
- Directory, Capacity, Referral, Matching, and Transfer use owner-local command idempotency. Same key/same fingerprint recovers the recorded result; same key/different fingerprint conflicts. Event-consumer deduplication remains a separate owner boundary. No global business receipt store exists.

## Observability and performance sanity

The full suite and local Compose smoke verify structured operational logs, correlation propagation, health, liveness, readiness, and the Prometheus endpoint. Normal request logs avoid payloads, Authorization headers, cookies, and patient-bearing query values. Metrics remain bounded and do not label PatientReference or high-cardinality case identifiers. Tracing-compatible instrumentation works without a collector.

Code and query review found bounded/pageable participant lists and batch owner contracts for candidate and capacity evidence reads. No concrete N+1 pattern, unbounded list, global reservation lock, global outbox lock, or other material performance pathology was identified. No unsupported throughput or national-scale benchmark is claimed.

## Compose smoke

`docker compose --project-name ayuvahak-v2-final-closeout-verified --env-file .env.example config --quiet` passed, and `up --build --detach` built and started a fresh isolated application/PostgreSQL/PostGIS/Keycloak stack. On loopback, health, liveness, readiness, `/api/v1/system-info`, `/actuator/prometheus`, and local Keycloak OIDC discovery returned HTTP 200. The stack used PostgreSQL/PostGIS and Keycloak images pinned by version and digest. The canonical database integration suite independently verified PostgreSQL/PostGIS behavior and the 37 Flyway migrations.

An unauthenticated protected Directory request returned 401. A locally minted synthetic client-credentials token without an AyuVahak grant returned 403, confirming fail-closed authorization. For the authorized smoke, a local-only synthetic Keycloak human and temporary facility-scoped grants were created only in this disposable project. Authenticated Directory facility, Capacity current-evidence, Referral list, Matching recommendation read, and Transfer list requests each returned HTTP 200. The Matching read returned an empty result for a synthetic nonexistent Referral ID; it did not exercise a new recommendation workflow. The returned Capacity evidence correctly reported `UNKNOWN` for the synthetic dimension with no observation. This smoke proves local authentication, scoped authorization, routing, and API availability; full business behavior is covered by the Testcontainers-backed API integration suites. The project was stopped and its volumes/network removed with `down --volumes --remove-orphans`; no Compose project or temporary synthetic grants remain.

## Canonical build and shutdown caveat

Command: `.\mvnw.cmd -B -ntp clean verify` using Java 25.0.4.1+1 and Maven 3.10.0.

- Result: `BUILD SUCCESS` (5 minutes 53 seconds).
- Surefire: 143 tests; 0 failures, 0 errors, 0 skipped.
- Failsafe: 225 tests; 0 failures, 0 errors, 0 skipped.
- Flyway: 37 migrations applied against disposable PostgreSQL/PostGIS.
- Spotless verification and package creation completed in the normal lifecycle.

The prior Phase 7 report's Testcontainers pool warnings and Surefire 30-second fork overrun were not reproduced in this run: Maven returned success, all XML reports completed with zero failures/errors, no fork-timeout text was emitted, and no Java test process remained. Two warnings from the newly introduced isolated Hikari fixture say the add-connection executor timed out during fixture shutdown; all three fixture tests passed, the lease is evicted after abort/lost response, and the warning did not reproduce in the application/Testcontainers suite. This is retained as minor test-fixture cleanup noise, not evidence of a production resource leak.

## Independent final review

The final `code-review` pass reviewed the closeout changes against the repository standards, Volume I architecture, Volume II contracts/specifications, and acceptance criteria.

- **BLOCKER:** 0 unresolved.
- **MAJOR:** 0 unresolved. Earlier closeout defects in referral/request race serialization and uncertain advisory-lock connection handling were reproduced, fixed with regressions, and included in the passing full build before this final review.
- **MINOR:** 2 retained observations: (1) Matching applicability validation/status mapping is repeated in two Transfer command paths and could drift; (2) Matching exceptions are mapped fail-closed to `TRANSFER_MATCHING_UNAVAILABLE`, which can obscure a deliberate semantic failure. Neither changes authorization/safety or violates the frozen specification; both are documented for future judgment and were not expanded into new features.
- **DOCUMENTATION:** 1 historical count ambiguity was corrected: Phase 7 freeze-time counts are explicitly historical and the separate Volume II closeout counts are in this record.

No review finding requires changing the approved Volume II architecture or starting Volume III.

## Open questions and scope

`docs/reviews/volume-2-open-questions.md` records **0 MUST RESOLVE BEFORE VOLUME II FREEZE** questions. Remaining items are deployment configuration or later-volume/deployment governance; none blocks correctness or this freeze.

The source, dependency, schema, and Compose audits found no Volume III interoperability adapter, FHIR/IPS/mCSD/SMART integration, DHIS2, OpenHIM/OpenHIE, OpenLMIS, Transport/ambulance dispatch, GPS/fleet, notification provider, public-health analytics/export, frontend/BFF, ML/LLM, Kafka/broker, Kubernetes requirement, or other Volume III implementation.

## Safety and freeze

- No external system was mutated; no public healthcare system was used as a test target.
- No remote Git operation was performed; no remote is configured. No PR, issue, release, package, image, or other artifact was published.
- No external account, real credential, real patient data, or PHI/PII was accessed or used.
- All tests and Compose services were local; all test data was synthetic.
- Volume I regression: **PASS** as part of the full clean suite, including platform startup/database/migrations, identity/grants, audit, event/outbox, idempotency, observability, and architecture enforcement.
- Volume II integrated result: **PASS / COMPLETE / FROZEN**.
- Authoritative local Volume II freeze commit: `a33b097ae793a599b27e3d53cad4a1b534bdf395` (`chore: freeze volume 2 core care coordination`).
- Volume III: **NOT AUTHORIZED / NOT STARTED**.
