# Phase 2 implementation review

**Review date:** 2026-10-08
**Scope:** Volume I Phase 2 platform kernel, identity, and trust, compared with the frozen Phase 0/1 architecture and `docs/specs/phase-2-platform-kernel-spec.md`.
**Review baseline:** Phase 1 freeze `d6aee6e9816df0cb76312e2d9df4be890aaaf0f4`; implementation through `fc6bca71` plus the working-tree audit remediation.

## Review result

The implementation remains within Phase 2. It adds platform identity/access, execution context, audit, provenance, event contracts, outbox delivery, idempotency/deduplication, and restricted operational review/recovery. It does not implement or scaffold a Volume II healthcare context. The remaining code-review observation is a low-risk duplication judgement call described below; there are no unresolved blockers or major defects.

The independent spec-axis review found no missing or incorrect requirements after the remediation. The independent standards-axis review found no documented-standard breach. It noted that three contexts repeat a small mapping from authentication mechanism to audit evidence. That repetition is retained to avoid adding a cross-context utility solely for three short mappings; it is not a correctness or standards defect.

## Module evidence

| Module | Implemented behavior | Evidence |
| --- | --- | --- |
| 2.1 Core primitives | Opaque UUIDv4 identifiers, actor/principal references, correlation and causation, explicit time, and provenance values. | `CorePrimitivesTest`, `ProvenanceTest`; ArchUnit core boundary checks. |
| 2.2 Organization/execution context | Explicit validated platform/organization/optional-facility context with human, service, and local operator principals; request context is not inferred from servlet state. | `ExecutionContextTest`, `LocalBootstrapExecutionContextTest`, `AuthorizationDecisionIT`. |
| 2.3 Authentication | Local signed JWT resource-server validation with configured issuer, audience, key source, algorithm/time constraints, and separate human/service classification. Keycloak is local reference infrastructure only. Invalid, absent, and untrusted-key outcomes are minimized and durably audited; audit failure fails closed. | `TokenPrincipalResolverTest`, `AuthenticationPropertiesTest`, `AuthenticationHttpIntegrationTest`, `KeycloakServiceTokenIT`, `AuditWriterIT`. |
| 2.4 Authorization | AyuVahak-owned, revocable RBAC-first grants over functional authorities and explicit context; cross-organization/facility access is denied. No IdP role is treated as authorization truth. | `AuthorizationServiceTest`, `AuthorizationDecisionIT`, `AccessGrantAdministrationIT`. |
| 2.5 Audit | Mutation audit shares the protected transaction; sensitive reads and non-mutating security outcomes use a durable fail-closed path. Restricted review is authorized, minimized, and itself audited. | `AuditWriterIT`, `AuditReviewApiIT`, `AccessGrantAdministrationIT`. |
| 2.6 Provenance | Reusable source/actor/method/time/confidence metadata; provenance stays with the fact owner rather than a shared repository. | `ProvenanceTest`, `ProvenanceOwnerFixtureTest`; ArchUnit ownership check. |
| 2.7 Event contract | Explicit UTF-8 JSON envelope, stable event names/version, unique event identity, aggregate sequence, and separate correlation/causation and occurrence/recording times. | `EventTypeTest`, `EventEnvelopeJsonCodecTest`, `AggregatePositionTest`. |
| 2.8 Transactional outbox/dispatcher | Publication intent is inserted with the owner transaction; restart recovery, bounded retries/leases, per-aggregate ordering, independent aggregate progress, no-handler retention/replay, and bounded delivered-event cleanup. | `OutboxTransactionPostgresIT`, `EventDeliveryOperationsIT`; disposable real PostgreSQL/PostGIS. |
| 2.9 Idempotency/deduplication | Command receipts and consumer receipts remain separate; exact retry, same-key/different-input conflict, concurrent duplicate protection, owner-local storage, and duplicate delivery behavior are verified. | `CommandIdempotencyValueTest`, `IdempotencyOwnerBoundaryIT`, `OutboxTransactionPostgresIT`. |
| 2.10 Kernel failure/recovery | Integrated failure tests exercise identity/provider failure, audit failure, grant revocation/scope denial, transaction rollback, dispatcher restart/retry/order, concurrent work, duplicate processing, and correlation propagation. | Full Failsafe suite; see [acceptance evidence](phase-2-acceptance.md). |

## Audit remediation

The initial independent review identified three contract gaps before freeze:

| Finding | Severity | Resolution |
| --- | --- | --- |
| Missing/invalid credentials and unknown signing-key source failure did not attempt the specified minimized durable security audit; audit failure did not consistently report its fail-closed outcome. | MAJOR | Added minimized evidence without actor, token, or raw header; `503 AUDIT_UNAVAILABLE` when durable capture fails; verified by HTTP and PostgreSQL integration tests. |
| Delivered-event retention was configured in the specification but had no required setting or purge path. | MAJOR | Added required positive retention configuration and bounded outbox-owned deletion of delivered events and their replay receipts; stalled/unhandled records are retained. Verified with PostgreSQL. |
| Idempotency lock waiting used a hard-coded two-second value instead of the specified configurable five-second default. | MINOR | Added bounded positive configuration, defaulting to five seconds, used by both command owners; defaults, override, and invalid bounds are tested. |

All three are resolved. The final independent standards review reported no documented-standard breaches. The final spec review reported no findings. One possible duplicated mapping remains a non-blocking judgement call, with context-local copies retained to avoid unnecessary cross-context coupling.

## Architecture enforcement and scope

The existing ArchUnit suite runs in the normal Maven verification lifecycle. Five architecture tests pass, including a test-scoped deliberate Spring-dependent core fixture that proves the prohibition is detected. Production source scans contain no Directory, Capacity, Referral, Matching, Transfer, Transport, Notifications, Analytics, or patient model packages. The only schemas introduced are owned platform schemas; no business schema exists. Dependency tree review found expected Spring Security/Jose and `hibernate-validator` only among the searched terms; no Hibernate ORM/JPA, HAPI/FHIR, DHIS2, broker, Redis, search, graph, AI, or workflow dependency was present. Keycloak remains a local container reference rather than a production Java dependency.

## Exact verified dependency baseline

- Java: Eclipse Temurin `25.0.4.1+1` used for this verification; compiler release 25.
- Spring Boot: `4.1.1`.
- Spring Security: `7.1.1`; Nimbus JOSE JWT `10.9.1`.
- Local Keycloak image: `26.8.0`, pinned by digest in Compose and Testcontainers.
- PostgreSQL/PostGIS: `postgis/postgis:18-3.6`, pinned by digest; runtime resolved to PostgreSQL `18.6` and PostGIS `3.6.4`.
- Flyway: `12.4.0`.
- Testcontainers: `2.0.5`.
- ArchUnit: `1.5.1`.
- Micrometer Prometheus: `1.17.1`.
- Spring Boot OpenTelemetry starter and OpenTelemetry API/SDK/exporter: Boot `4.1.1`, OpenTelemetry `1.62.0`.
- Maven Wrapper `3.3.4`, pinned Maven distribution `3.10.0`.

## Verification and limitations

The final clean verification command was `.\mvnw.cmd -B -ntp clean verify`: **86 unit tests and 55 integration tests passed**, zero failures/errors/skips; ArchUnit, Spotless, and package verification passed. The integration tests used disposable local PostGIS and local Keycloak containers. Compose config validation, image build, startup, app/database readiness, OIDC discovery, and a synthetic service-token authorization smoke check also passed; details and output codes are in [Phase 2 acceptance](phase-2-acceptance.md).

The Compose smoke path uses loopback-only ports `18080` and `18081`; the stack was shut down without removing its local database volume. No remote system or public healthcare/identity service was used. No real credential or patient data was accessed. This is an implementation review, not a penetration test, legal/privacy certification, production deployment review, or workload/SLO benchmark.

No Phase 0 architecture deviation was made. No Volume II functionality was started.
