# Phase 10 — Emergency Transport Coordination Acceptance

**Result:** PASS. Phase 10 passed its local verification and independent review and is ready for its local freeze commit. Volume III still requires its separate post-freeze integration gate and final review.

**Scope:** Transport coordination for an existing Transfer, including manual operation and the optional Phase 9 partner boundary. No live EMS, provider, routing, or healthcare system was used. No Volume IV work was started.

## Approved ticket graph

The user approved the eight-slice graph and its TDD seams. Tickets P10-01 through P10-08 are stored locally under `.scratch/phase-10-emergency-transport-coordination/issues/`. Implementation proceeded sequentially; no implementation worktrees or external issue tracker were used.

```text
P10-01 eligible request and manual baseline
  → P10-02 assignment, status, and ETA evidence
  → P10-03 movement evidence and Transfer reconciliation
  → P10-04 cancellation and assignment-race recovery
  → P10-05 Transfer-owned post-movement destination change
      ├→ P10-06 local provider adapter through Phase 9
      └→ P10-07 scoped views and operator reconciliation
          → P10-08 integrated acceptance
```

## Implemented behavior and ownership

- Transfer verifies request eligibility and owns its acceptance, capacity relationship, departure, arrival, handover, completion, cancellation, and active destination.
- Transport owns its request, provider/manual assignment reference, status and ETA observations, cancellation and destination-change operations, movement evidence, and reconciliation state.
- Capacity remains the sole owner of reservation effects. Matching remains the owner of destination recommendations.
- Cross-context interaction uses owner contracts and named events. Transfer and Transport do not access one another's private persistence.
- Manual operation works without a provider. The optional provider path uses the Phase 9 trust and transport boundary with local controlled mocks.
- Transport persists no PatientReference, diagnosis, name, clinical summary, attachment, vehicle inventory, or GPS trail. Provider status and ETA are evidence, not guaranteed outcomes; arrival remains distinct from handover.
- Pending or unknown provider/capacity outcomes remain visible and block conflicting replacement operations. Stable operation identities and owner-local idempotency support recovery without claiming exactly-once processing.

## Verification evidence

### Canonical build

Command:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

The final clean run passed on Temurin Java 25.0.4.1 with Maven Wrapper/Maven 3.10.0 and Spring Boot 4.1.1. Surefire: **313 tests**; Failsafe: **292 tests**; failures **0**, errors **0**, skips **0**. Spotless and package verification passed. Flyway validated and applied **62 versioned migrations**, ending at V62. PostgreSQL/PostGIS integration tests used the repository's digest-pinned PostGIS 18-3.6 image; the database reported PostgreSQL 18.6 and PostGIS 3.6.4.

The first full clean run had one non-reproduced timeout in the Phase 2 `IdempotencyOwnerBoundaryIT.concurrentExactRetryWaitsForTheOwnerTransactionAndCreatesOneEffect` test while polling `pg_stat_activity` for a lock wait. The exact integration class then passed in isolation (4 tests), and the subsequent full clean run passed all 292 Failsafe tests. No product failure was reproduced and no test assertion was weakened. This isolated observation is recorded rather than hidden.

### Phase 10 integration and architecture seams

- `TransferJourneyCommandsApiIT`: **102 tests**, 0 failures/errors/skips. This class exercises eligible request creation; scoped views; manual assignment/status/ETA; ordered and delayed movement evidence; distinct Arrival/Handover/Completion; cancellation races and recovery; Capacity release/reconciliation; reroute freshness/acknowledgement; audit and idempotency failure behavior; and local PostgreSQL concurrency.
- `PartnerTrustAdministrationIT`: **15 tests**, 0 failures/errors/skips. The provider submission, response loss, revoked trust, and operation recovery paths use local exchange fixtures. No public provider was contacted.
- `Phase1ArchitectureTest`: **14 tests**, 0 failures/errors/skips, in the ordinary Surefire lifecycle. Volume III rules run in the canonical suite; the production boundary prevents domain-to-provider/HTTP dependencies and cross-context persistence access.
- Phase 9 exchange architecture remains semantically neutral; Transport owns operation meaning and provider mapping. No provider SDK, EMS client, generic exchange ledger, or external URL input was added.
- The final review found one MAJOR issue: conflicting provider evidence and changed idempotency fingerprints were rejected without durable non-mutating audit evidence. A regression was added first and failed for the expected missing audit; the bounded fix now records minimized conflict evidence after the rejected transaction, and returns a safe audit-unavailable outcome if that audit cannot be written. The focused regression passed. Review follow-up found no unresolved BLOCKER or MAJOR.

### Local Compose smoke

`docker compose ... config --quiet` passed. A clean, isolated Compose project was started from the current source with only the repository's local PostgreSQL/PostGIS and Keycloak services. The database applied 62 migrations and returned a real `postgis_full_version()` result. The following returned HTTP 200: health, liveness, readiness, `/api/v1/system-info`, Prometheus metrics, and local Keycloak discovery with the configured issuer.

The one-shot bootstrap was run using a synthetic operator and service identity against the disposable local database through `BootstrapGrantApplication` and Spring Boot's `PropertiesLauncher`; it completed with exit code 0. A locally issued service token then made an authorized `GET /api/v1/platform/access-grants` with explicit `PLATFORM` execution scope and received HTTP 200. No token or local credential is included in this record.

The Compose project used a unique name and localhost-bound ports. Its database volume and network are removed after smoke verification. No other Compose project is targeted.

## Persistence, dependencies, and scope audit

The fresh Compose database contained these non-system schemas: `audit`, `capacity`, `directory`, `identity_access`, `matching`, `platform_eventing`, `platform_exchange`, `public`, `referral`, `tiger`, `topology`, `transfer`, and `transport`. A catalog query found **0 cross-schema foreign keys** involving business schemas. Transport migrations are V43–V62; business schemas are created by their owning contexts.

Phase 10 added no production dependency. The frozen baseline remains Java 25, Spring Boot 4.1.1, Maven Wrapper, Flyway, PostgreSQL/PostGIS, Spring JDBC, ArchUnit 1.5.1, and Testcontainers 2.0.5. Phase 8/9 dependencies remain HAPI FHIR 8.8.2 for R4/validation and Apache HttpClient 5.6.5 / HttpCore 5.4.4 for the Phase 9 boundary. No HAPI server/JPA server, Hibernate ORM/JPA, jOOQ, broker, Redis, search/graph database, cloud SDK, routing SDK, AI/LLM library, or provider-specific healthcare SDK was introduced for Phase 10.

Source/schema/dependency review found no Phase 11 or Volume IV implementation: no DHIS2, analytics, Transport fleet, dispatch optimization, GPS surveillance, route service, or live EMS connector. The `transport` schema and bounded context are the only Phase 10 business addition.

## Review and known limitations

The Phase 10 implementation was reviewed against `AGENTS.md`, the frozen Volume I–II architecture, the Volume III architecture/contracts/state machines/threat model, ADR 0025, and the Phase 10 specification. The conflicting-evidence audit issue was fixed and retested. Remaining BLOCKER: **0**; MAJOR: **0**; no unresolved review finding prevents Phase 10 freeze.

Provider acceptance is intentionally local-only. The provider remains authoritative for its fleet and dispatch algorithms; this system does not claim actual dispatch or real-world movement. The initial lock-wait test timeout was not reproduced by either the focused class rerun or the final full suite and remains a documented test-timing observation, not a known production defect.

## Safety

All verification used synthetic identifiers and local containers/mocks. No public healthcare/EMS system, external IdP, production credential, real patient information, or external service was accessed or mutated. No remote Git operation, publication, deployment, issue, or pull request was performed. Phase 10 completion does not authorize Volume IV.
