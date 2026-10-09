# Phase 5 Implementation Review — Referral

**Result:** PASS. Phase 5 is implemented and locally frozen. Phase 6 and Phase 7 were not started.

## Scope delivered

Phase 5 implements the Referral coordination lifecycle defined by the [canonical specification](../specs/phase-5-referral-spec.md): scoped draft creation and editing; submission with immutable revisions; amendment and explicit resolution; cancellation; and audited, scoped current/revision/list reads. It keeps an opaque issuer-scoped `PatientReference` within Referral, excludes it from list results and cancellation events, and provides no patient identity or clinical-record service.

Cancellation records the minimal `referral.cancelled.v1` event and its delivery row atomically with Referral state, required audit evidence, and idempotency receipt. While the Transfer handler is not installed, delivery begins in the durable `WAITING_FOR_CONSUMER` state. Waiting events are not claimed, retried, replayed, or promoted automatically. No Transfer behavior was implemented.

The Referral module validates Directory-owned facts through its application contract. It does not read Directory persistence. The future `GetTransferBasis` operation remains a design contract only; a callable recipient view waits until Transfer can prove a real request and participant relationship.

## Phase 5 ticket graph

Five sequential local tickets were completed in the approved order: P5-01 draft creation/editing; P5-02 submission/revisions; P5-03 amendment/resolution; P5-04 cancellation/deferred delivery; P5-05 scoped queries/privacy/acceptance. The graph is recorded in [the local tracker](../../.scratch/phase-5-referral/map.md). Later slices depended on lifecycle semantics established by earlier slices, so none ran concurrently.

## Material versions and files

| Component | Selected version or artifact |
| --- | --- |
| Java | Eclipse Temurin `25.0.4.1+1` |
| Spring Boot | `4.1.1` |
| Maven Wrapper | Apache Maven `3.10.0` |
| Flyway | `12.4.0` |
| PostgreSQL/PostGIS | `postgis/postgis:18-3.6` pinned by manifest digest; runtime resolved to PostgreSQL `18.6` and PostGIS `3.6.4` |
| Testcontainers | `2.0.5` |
| ArchUnit | `1.5.1` |
| PostgreSQL JDBC | `42.7.13` |
| Prometheus registry | Micrometer `1.17.1` |
| Tracing bridge / OTLP exporter | Micrometer tracing `1.7.1` / OpenTelemetry exporter `1.62.0` |
| Local reference IdP | Keycloak `26.8.0`, pinned by manifest digest |

The implementation adds Referral-owned domain, application, API, and persistence code under `src/main/java/org/ayuvahak/referral/`; authenticated API integration tests under `src/test/java/org/ayuvahak/referral/`; and Referral migrations V14–V18 under `src/main/resources/db/migration/`. A test-only `DirectoryCapacityReferralScenarioIT` exercises the integrated Directory→Capacity→Referral path with synthetic fixtures. No dependency or platform-wide architecture baseline changed during Phase 5.

## Review findings

The independent canonical-spec review found no actionable finding, missing behavior, or scope creep. The independent standards review found no architecture-standard violation. It recorded three non-blocking maintainability observations: repeated organization/facility and Directory-reference validation across command/query paths; repeated request execution-context parsing in the API adapter; and two positional booleans in a request parsing call. These do not change behavior or violate a boundary, so no aesthetic refactor was made in this freeze pass.

No unresolved blocker or major implementation finding remains. The only Phase 5 specification adjustment is a targeted clarification that the recipient-facing `GetTransferBasis` runtime operation cannot exist until Phase 7 can validate Transfer participation; this preserves the approved data-minimization and authorization boundaries.

## Known boundaries and limitations

- Referral reads are origin-scoped. Receiving-side access to the source PatientReference requires a real Transfer Request and a validated participant, which Phase 5 does not own.
- Matching, destination recommendation, Transfer, Acceptance, Reservation, Transport, FHIR/IPS, and clinical context exchange are outside Phase 5.
- Deployment-specific retention and jurisdictional privacy requirements remain governed by deployment policy; Phase 5 does not invent retention durations.
- The local Compose host's default app port `8080` was unavailable during smoke verification. The ignored local `.env` used `18080`/`18081` for that run; no tracked Compose default changed.

## Verification

The full integrated command was `./mvnw -B -ntp clean verify` (Windows: `./mvnw.cmd -B -ntp clean verify`), run with the installed Java 25 toolchain. It passed with 100 Surefire tests and 148 Failsafe tests, zero failures/errors/skips, and Spotless clean. The real disposable PostGIS image exercised migrations through V18 and Referral HTTP/PostgreSQL behavior, including audit/idempotency, privacy, scope, concurrency, and deferred cancellation delivery. The dedicated cross-phase scenario separately created Directory-owned organization/facility/capability records, recorded and queried Capacity readiness, then submitted a Referral that referenced the CareCapability.

The full review and command-by-command results are in [Phase 5 acceptance evidence](phase-5-acceptance.md). No Phase 0 architectural deviation occurred. Phase 6 and Phase 7 remain unimplemented.
