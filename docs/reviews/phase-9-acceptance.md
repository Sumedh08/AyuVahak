# Phase 9 — Secure External Health-System Exchange Acceptance

**Result:** PASS, subject to the bounded limitations recorded below. Phase 9 is locally frozen after Phase 8 passed and froze. Phase 10 remains unimplemented and unauthorized.

## Scope and approved graph

The user-approved local graph contained seven vertical slices:

1. P9-01 — versioned partner trust profiles.
2. P9-02 — vetted outbound HTTP transport.
3. P9-03 — secret resolution and partner OAuth client authentication.
4. P9-04 — Directory-owned mCSD outbound reads.
5. P9-05 — Referral-owned IPS source retrieval.
6. P9-06 — named inbound Directory feed and replay recovery.
7. P9-07 — integrated security, privacy, and acceptance.

P9-01 through P9-03 establish the shared technical trust/transport prerequisites. P9-04, P9-05, and P9-06 retain Directory and Referral semantics at their owner boundaries. P9-07 integrates and verifies those paths. Tickets and their dependency graph are local Markdown under `.scratch/phase-9-secure-external-exchange/`; no external issue tracker was used.

## Implementation and ownership

- Platform Exchange owns versioned partner trust configuration, technical HTTP/TLS/OAuth transport, safe failure classification, and trust-administration audit. It does not own mCSD/IPS mapping or business outcomes.
- Directory owns source namespace binding, mCSD mapping, imports, reconciliation, provenance, and the ITI-130 replay receipt. Feed changes enter only through Directory's owner application transaction.
- Referral owns PatientReference, source selection, disclosure authorization, purpose/policy evaluation, fail-closed audit, and the transient IPS projection. Transfer's selected-participant check remains a separate prerequisite.
- No generic external-operation ledger, generic webhook, URL-fetch API, raw exchange archive, or Phase 10/Transport module was added.

The generic outbound boundary accepts an owner-authored operation path and bounded query values. It does not hardcode mCSD or IPS meaning. HTTP redirects remain disabled. Endpoint metadata imported from FHIR remains inert and cannot create a trusted partner profile.

## Material implementation details

- Java runtime and compiler baseline: Java 25; local verification used Temurin 25.0.4.1.
- Spring Boot: 4.1.1.
- Maven Wrapper: Apache Maven 3.10.0.
- Phase 8 FHIR libraries retained: HAPI FHIR 8.8.2 R4 structures/validation modules.
- Phase 9 HTTP transport: Apache HttpClient 5.6.5 and HttpCore 5.4.4. A client-scoped resolver validates the complete DNS answer set, pins the socket to the vetted address, preserves the configured host for TLS verification, and verifies the peer. Per-operation clients avoid connection reuse across partner/configuration trust boundaries.
- Testcontainers: 2.0.5; ArchUnit: 1.5.1. PostgreSQL/PostGIS tests use disposable real containers.
- Local Compose images are digest-pinned: PostgreSQL 18/PostGIS 3.6 and Keycloak 26.8.0. No external partner was configured.
- Migrations V40–V42 create the platform-owned `platform_exchange` trust schema and the Directory-owned `directory.mcsd_feed_receipt`. The latter is owner-scoped replay state, not a platform-wide business receipt store.

## Trust, authorization, and failure behavior

Partner profiles are versioned, administrator-controlled, and authorized/audited. Business requests cannot supply a destination URL. New sends and each bounded retry revalidate the currently active profile version; suspension/revocation invalidates cached credentials and blocks queued work before network use. TLS hostname verification stays enabled, automatic redirects are disabled, destination and response bounds apply, and secret/token bytes are not persisted in trust rows or operational metadata.

Client-credentials OAuth is implemented for client-secret Basic and `private_key_jwt` using local secret references and local token endpoints. Token issuer, audience, signature, algorithm, time claims, and client binding are validated. `MUTUAL_TLS` profile configuration fails closed as unsupported in the current adapter; no Phase 8/9 owner operation requires it. No interactive OAuth flow or external client registration exists.

Outbound safe reads retry only configured transient failures within bounded attempts and duration. DNS, forbidden destination, TLS, timeout, oversized response, peer mismatch, remote authentication, remote authorization, rate-limit, server-error, unsupported authentication, and revoked-profile outcomes remain distinguishable. Retry-After is bounded. No exactly-once claim is made.

## Owner integrations

### Directory / mCSD

ITI-90 discovery and configured ITI-91 changes-since reads use the approved active partner profile and Directory's existing mapping/import path. Source namespace binding occurs before source access/checkpoint use. Continuation values remain opaque owner data and are not treated as destinations. Partial responses do not infer deletion; source-version ordering, duplicate updates, conflicts, stale evidence, and explicit status/deactivation observations preserve Phase 8 reconciliation semantics. A transaction DELETE requires the source version precondition and records Directory-owned inactive-source evidence requiring reconciliation; it does not silently deactivate the operational Directory record. ITI-130 is a named inbound feed into the Directory owner contract; no external-master write-back exists.

### Referral / IPS

The local-source integration test exercises Referral's existing disclosure application path with Transfer participation, Referral grants, purpose/policy checks, and fail-closed audit before/after retrieval. The owner adapter returns an allowlisted transient projection. No full Bundle, Patient, Composition, or clinical cache is stored. Source failure or revoked partner produces no clinical result and does not change Transfer state.

## Security, privacy, and architecture evidence

- Deterministic local DNS tests cover forbidden/mixed answer sets and rebinding; the HTTP integration test proves socket pinning to the vetted peer while preserving hostname verification.
- Local TLS tests cover valid trust, wrong hostname/CA, token endpoint behavior, redirect denial, response bounds, decompression bounds, and operation timeout. No trust-all production path is configured.
- Local OAuth tests cover client-credentials construction, signature/issuer/audience/time validation, secret/token redaction, and redirect/error-body handling.
- Local exchange tests cover active/draft version changes, suspension/revocation, queued-send trust revalidation, safe retry limits, and distinct 401/403/429 classifications.
- Directory integration tests use synthetic mCSD bundles and real PostgreSQL to verify owner imports, scoped rejection, replay, changed-fingerprint conflict, source status evidence, and transactional audit. A reconstructed Directory feed service instance reads the durable PostgreSQL receipt to verify restart-equivalent recovery. The suite does not restart the application container mid-request.
- Referral tests verify selected-participant and disclosure grants, policy and audit failure, invalid/mismatched source, unavailable source, and transient projection. Synthetic patient/clinical markers are checked against generic metadata and diagnostic surfaces.
- ArchUnit executes in the normal Maven verification lifecycle against production classes. It enforces FHIR placement in the platform boundary/approved owner adapters, framework-independent domains, no cross-context persistence dependency, and the existing context constraints. Test-only invalid fixtures demonstrate FHIR/domain and cross-context persistence violations are detected.
- A source/package/dependency audit found no production Transport/EMS, DHIS2, public healthcare client, generic webhook, FHIR server, broker, Redis, search/graph database, cloud SDK, or AI/LLM implementation. `hibernate-validator` is not present as Hibernate ORM; no Hibernate ORM/JPA or jOOQ dependency was found.

The complete dependency tree was generated locally with `mvnw dependency:tree`. The only Phase 9 runtime dependency addition is Apache HttpClient 5 (and its managed HttpCore 5 dependency). The direct HAPI 8.8.2 selection remains the R4 structures and validation modules. As already disclosed in the frozen Phase 8 acceptance record, HAPI's validation aggregate brings transitive converter and model artifacts for R4B/R5 and earlier releases; its validator initializes an R5 context internally. AyuVahak still accepts/maps FHIR R4 only and has no R5 adapter or conversion path. This is an existing Phase 8 library footprint, not a Phase 9 addition. No HAPI server, JPA persistence, FHIR search server, or terminology server module was added.

## Verification evidence

Canonical command:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Result: **PASS** on Java 25.0.4.1 / Maven 3.10.0. The final report artifacts show 297 Surefire tests across 56 report classes and 246 Failsafe tests across 17 report classes; failures 0, errors 0, skipped 0 in both suites. Maven verification includes compile/package, Spotless check, architecture tests, and Testcontainers-backed PostgreSQL/PostGIS integration tests. Flyway applied 42 migrations.

Compose evidence:

- `docker compose --env-file .env.example -p ayuvahak-p9-final config --quiet` passed.
- `docker compose ... up -d --build` built the application and ran PostgreSQL/PostGIS and local Keycloak. Windows would not bind default port 8080, so the same isolated project was retried on loopback ports 18080/18081. This was an environment port conflict, not an application failure.
- Local readiness, liveness, overall health, `/api/v1/system-info`, Prometheus metrics, and Keycloak discovery returned successfully. The local Keycloak `transfer-agent` client obtained a synthetic client-credentials token. An authenticated partner-trust read returned 403 because the disposable local service identity had no AyuVahak partner-administration grant; this is the expected fail-closed authorization outcome. Protected profile operations with authorized synthetic operators are covered by integration tests.
- The isolated project was stopped and its containers, network, and database volume were removed with `down --volumes --remove-orphans`.

Schema inspection against the local Compose PostgreSQL instance reported application schemas `audit`, `capacity`, `directory`, `identity_access`, `matching`, `platform_eventing`, `platform_exchange`, `referral`, and `transfer`, plus PostgreSQL/PostGIS schemas `public`, `tiger`, and `topology`. It confirmed 42 Flyway migrations and PostGIS 3.6.4. There is no `transport`, `ems`, `fhir`, `dhis2`, analytics, or patient-warehouse schema. Migrations V40–V42 contain no cross-context foreign key; replay receipt ownership remains in `directory`.

## Independent review and remediation

The independent standards-axis review confirmed no standards-boundary breach. The independent spec-axis review found no remaining actionable issue. Earlier review findings were fixed and covered by regressions:

- suspension/revocation now targets the active profile even when a newer draft exists;
- queued and retried sends revalidate current trust before network use;
- configured bounded retries now execute only for safe idempotent reads;
- remote authentication, authorization, rate-limit, and server-error outcomes remain distinct;
- generic transport no longer owns mCSD/IPS semantic paths or operation-code interpretation;
- version-conditioned ITI-130 deletes now enter Directory-owned source-status reconciliation evidence and are audited transactionally.

Final review result: **0 BLOCKER, 0 MAJOR, 2 MINOR observations, 0 DOCUMENTATION findings**. The two minor observations were potential duplication of protocol contract literals and separate authentication-method switches; reviewers considered them judgment calls, found no correctness defect, and recommended no scope-expanding cleanup. No unresolved BLOCKER or MAJOR remains.

## Limitations and scope

- No real partner onboarding, public healthcare service, public sandbox, external IdP, or external healthcare network test was performed.
- Mutual-TLS client authentication is not implemented; selecting that profile is rejected safely. Current named owner integrations use the implemented client-credentials mechanisms.
- The inbound-feed restart seam reconstructs the owner service against the same durable database rather than killing/restarting an application container during an HTTP response. The database-backed receipt and transaction are covered; process-level crash injection remains a later test-hardening opportunity.
- Compose smoke exercised the platform/runtime endpoints and local Keycloak, not a configured external partner flow. mCSD, IPS, DNS, TLS, OAuth, replay, and owner-boundary behavior are exercised by local mock/Testcontainers tests.
- No Phase 0 architectural deviation was made. No Phase 10 or Volume IV implementation was started.

## Safety and freeze

All application integration targets were local Testcontainers, loopback HTTP/TLS mocks, local Keycloak, or deterministic fixtures with synthetic values. No real patient data or production credential was accessed. No external healthcare system was mutated, no public healthcare system was used as a test target, and no Git remote operation, external account creation, issue/PR/release, deployment, package publication, or container publication occurred. Git activity remained local and no remote is configured.

Phase 9 freeze commit: to be recorded in the documentation-only follow-up commit after the freeze commit is created. Phase 10 remains specification-frozen / implementation not authorized.
