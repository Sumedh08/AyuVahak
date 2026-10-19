# Phase 13 — Production Hardening and Digital Public Goods Readiness

**Status:** Proposed specification; pending independent review and Volume V design freeze. No Phase 13 implementation or production deployment is authorized by this document.

## 1. Objective

Make the existing AyuVahak product responsibly deployable, upgradeable, observable, secure, supportable and recoverable, and produce evidence-based readiness assessments against the current public Digital Public Goods (DPG) Standard. Phase 13 does not assert DPG certification, registration, legal compliance or a universal production service level.

## 2. Scope and preserved ownership

Phase 13 adds operating configuration, verification, deployment documentation, recovery and release evidence around the existing modular monolith. Existing owner contexts, schemas, PostgreSQL authority, Phase 9 trust, Phase 11 federation/notification, Phase 12 Analytics, grants, audit, eventing and privacy semantics remain unchanged. Operational tooling must not become a new business authority.

### In scope

- Supported deployment profiles and a portable production configuration contract.
- Deployment-owned SLI/SLO and capacity-baseline declarations.
- PostgreSQL backup, restore, recovery, migration and replica-operating procedures.
- Multi-replica worker safety, health/readiness, shutdown and management-plane controls.
- Secret reference/provider boundary, rotation and TLS/key operations.
- Layered resource abuse limits.
- Security verification mapped to versioned guidance.
- SBOM, build provenance, artifact signing/verification and vulnerability handling design.
- Incident, recovery, retention, operator and adopter documentation.
- Dated DPG and production-readiness matrices with evidence and governance gaps.

### Non-goals

- No mandatory Kubernetes, cloud vendor, microservices rewrite, broker, Redis correctness dependency or new business context.
- No production deployment as acceptance, external penetration test, DPG submission or certification claim.
- No jurisdiction-independent legal, clinical, retention, availability, RPO or RTO guarantee.
- No telemetry backend, security vendor, release-signing identity, or cloud secret product mandated by core architecture.
- No change to product license without an explicit rights-holder decision.

## 3. Deployment profiles and configuration contract

Support both portable profiles defined in the [Volume V architecture](../architecture/volume-5-production-hardening-optimization-intelligence.md): single-site and highly available institutional. Compose remains the local verification topology and is not represented as production configuration. Operators may implement either profile with a supported platform of their choice if it satisfies the same invariants.

Production configuration must be explicit, validated at startup, environment/provider separated, and documented with safe examples. There must be no production fallback to development secrets, trust-all TLS, wildcard CORS, local Keycloak development mode, exposed administrative actuator, caller-controlled endpoint, or permissive authentication. Required dependency failure prevents readiness or the protected operation; optional peer failure must not cause a liveness restart loop.

The release/deployment profile records app/runtime/database/IdP versions, artifact digests, enabled integrations, secret references (never values), TLS/issuer/audience policy, management exposure, rate limits, SLOs, RPO/RTO, retention policy, logging/metrics export, backup topology and accountable operator. Sensitive config is not returned through diagnostics.

## 4. Service objectives and measurable capacity

Each deployment chooses from the SLO classes of synchronous API availability/latency, required dependency/database health, durable audit/outbox/notification lag, reconciliation/exchange/federation age, Analytics/DHIS2 backlog/unknown age, and recovery objectives. It supplies target, window, service scope, error definition, exclusions, alert, owner and response for each selected objective; omitted classes require a documented rationale. The platform provides a documented SLI catalogue: API availability and latency by bounded route class; database readiness/pool saturation; required audit-write failures; internal outbox and projection lag; reconciliation/exchange/federation operation age; SSE connection/overflow; Analytics and DHIS2 backlog/unknown age; process/resource exhaustion. No default percentage or emergency-grade claim is shipped as universal policy.

Error budgets are optional lightweight operational thresholds, not a requirement for an SRE platform. Crossing an operator-selected threshold triggers a documented review/mitigation action; ordinary rejected domain commands do not count as service failure unless the declared SLI says so. Metrics and alerts have bounded labels and never use patient, referral, transfer, transport, snapshot, remote resource or credential identifiers.

Before performance changes, run a versioned synthetic workload declaration covering organizations, facilities, active referrals, transfers/day, capacity observations/minute, connected SSE clients, federation peers, indicator/period count and export operations. Record environment and data scale, p50/p95/p99, throughput, errors, resource use, DB connection/query/plan behavior and durable-work lag. Thresholds are deployment profile inputs. Results do not imply scale outside the measured envelope.

## 5. Database backup, restore and recovery

PostgreSQL/PostGIS stays authoritative. Each production plan specifies backup method, scope, cadence, retention, encryption, access separation, off-host protection, configuration coverage, key/secret restoration responsibility, WAL/base-backup integrity when PITR is selected, archive lag/disk alerts, recovery target selection and named owner. Replication is not a backup.

Each profile declares an RPO and RTO with units and measurement method. Targets may differ by deployment; there is no platform default. The selected architecture must show how the targets can be achieved, including identity/configuration dependencies and acceptable consistency point.

Acceptance performs a restore to a clean, isolated, disposable PostgreSQL/PostGIS environment using synthetic state. It starts the application at an explicitly supported migration version; verifies migrations, owner records, grants/audit, event/outbox state, idempotency, and representative Referral/Transfer/Transport/federation/Analytics invariants; exercises authorized read paths; records elapsed recovery time and data-loss boundary; compares both to declared targets; and stores sanitized evidence. A backup job status or replica promotion alone fails this criterion.

Corruption, incomplete WAL, missing encryption key/configuration, or failed invariant validation produces a failed restore gate and operator escalation. No restored deployment accepts mutations until the restore validation policy permits it.

## 6. Upgrade and migration safety

Every upgrade documents supported source/target version, application/runtime/database compatibility, release notes, migration inventory, pre-upgrade backup proof, rollout order, expected lock/downtime characteristics, mixed-version compatibility, validation and recovery path. A single controlled migrator applies schema changes before new replicas serve traffic. Worker/mutation admission is blocked when schema state is unknown or migration is incomplete.

Use additive/expand-contract migration where mixed versions are required. Do not promise automatic down migration. On failure, stop rollout and use a tested forward repair or a verified restore/recovery plan; never mark a partially migrated instance ready. Upgrade acceptance covers successful upgrade, interrupted/failed migration, restart and compatibility checks.

## 7. Application topology and operations

Application nodes hold no correctness state solely in memory. Existing database-backed claims, leases, idempotency, audit, outbox, notification cursors, federated operations and Analytics/export barriers remain the durability mechanism. Two application replicas running duplicate outbox, notification, reconciliation and Analytics workers against one test database must not create duplicate business effects or lose durable work.

Graceful shutdown stops new admission, gives bounded time to in-flight requests and worker units, closes SSE connections, releases connections/executors and leaves uncompleted durable operations recoverable. Process-local connection loss is not an operation outcome.

Health contracts distinguish process-local liveness, database-dependent readiness and optional dependencies. Public health output is minimal. Management endpoints are allowlisted, protected or isolated to the operator plane, and unavailable to untrusted ingress. Monitoring exports through configured deployment endpoints and is disabled safely when no backend is configured.

## 8. Security, secrets and abuse protection

Create a version-pinned application/API security verification matrix using applicable OWASP ASVS 5.0.0 requirement IDs, test/evidence links, exclusions and rationale. Use OWASP API Security Top 10 (2023) only as a risk prompt. Map practices to final NIST SSDF v1.1; treat SSDF v1.2 IPD as non-final and recheck at implementation. This is an internal verification plan, not certification.

Retain frozen authentication, authorization, audit, Phase 9 endpoint/TLS/SSRF, Phase 11 peer and Phase 12 disclosure controls. Add production-profile negative tests for unsafe defaults, management exposure, CORS/TLS policy, secret presence and endpoint configuration. Rate limiting distinguishes anonymous/bootstrap, authenticated reads, sensitive writes, administration, SSE, federation ingress and exchange ingress. Apply principal/service, tenant/scope, partner and operation budget; IP edge limits are supplementary. Limits are configurable, observable, bounded and never substitute for authorization.

Use administrator-provided secret references and a provider-neutral resolution port. Production required credentials fail closed at startup or use time. Secret values stay outside source, business tables, logs, audit payload and diagnostics. Rotation has an overlap/expiry procedure for OIDC/JWKS, TLS, federation, Phase 9 and signing identities; revocation invalidates caches within defined bounds. Test with a local fake provider and local TLS only.

## 9. Supply chain, release and vulnerabilities

For each distributable release artifact, generate a validated SPDX 3.0 SBOM with documented coverage, component identities/versions, relationships, known license expressions and artifact hashes. The selected generator/profile must be checked for SPDX 3.0 support during implementation. One canonical format is used initially; CycloneDX is an alternative only if a concrete consumer need is evidenced.

Release evidence includes artifact digest, machine-verifiable SLSA provenance, and authenticated signature or signed manifest covering artifacts and evidence. Publish verifier instructions and expected source/builder/signer identity. Select an interoperable signing implementation at implementation time. Do not claim SLSA level, signature trust, security or complete reproducibility based on the presence of CI or an SBOM. Build provenance must identify source, builder, build type/parameters and outputs by digest; verification checks identity and expected parameters.

Pin deployable container artifacts by digest and document supported runtime/base-image update cadence, compatibility and emergency patch path. Immutable pinning must not defer security updates.

Maintain dependency inventory and automated vulnerability monitoring. Triage uses severity, exposure/reachability, exploit evidence/KEV, available fix and deployment context. Define response classes, critical exploitable escalation, fix verification, false-positive evidence and a time-bounded exception with owner, compensating controls and expiry. Zero findings is not a security assertion. No external target scanning is part of acceptance.

## 10. Observability, incident response and retention

Document dashboards/alerts for the SLI catalogue and distinguish immediate security/data-correctness incident, service availability, and backlog/degradation. Logs are diagnostic, audit is restricted accountability evidence, and metrics/traces are aggregate operational signals. Apply access control and configurable retention to each; prevent PHI, prompts, secrets, tokens, raw clinical payload, high-cardinality case identifiers and uncontrolled partner response bodies from telemetry.

Required runbooks cover database loss/restore, IdP outage, secret/certificate compromise or rotation, exchange/federation outage and unknown outcome, stuck outbox, audit-store failure, notification degradation, disk/connection exhaustion, failed migration, vulnerable dependency, suspected breach, and unsafe configuration. Each has detection, containment, authority, safe fallback, communication/recordkeeping and recovery validation. Define whether read-only maintenance mode is supported; it must be explicit and must not allow unsafe partial writes.

Create a retention matrix by data category and owner: operational context facts, audit, outbox/receipts, exchange and federation operations, notifications/cursors, Analytics snapshots/revisions/disclosures and export history, logs, traces and backups. For each state purpose, access, configurable duration, legal/governance owner, expiry/purge/pseudonymization behavior and invariant/history dependencies. No jurisdiction-specific duration is hard-coded. Retention/deletion never silently corrupts audit, disclosure history, idempotency or unresolved-outcome recovery.

## 11. Digital Public Goods readiness

Maintain the [dated DPG gap analysis](../reviews/dpg-readiness-gap-analysis.md) against the current public DPGA basis. At retrieval, official materials show latest numbered Standard release v1.1.6 while the current public page and October 2026 Practical Guide contain later/current criterion material and 2025 AI clarifications; the relationship is not unambiguous. The analysis therefore names the basis and preserves that caveat, rather than inventing a Standard version.

For each criterion, separate product evidence, deployment evidence, and owner/governance evidence. Before any DPG claim, the accountable owner decides software license and ownership/redistribution rights, responsible stewardship, public privacy/legal and safety evidence, applicable content/harassment processes, portability, accessibility/standards evidence and SDG/impact basis. Accessibility/localization assessment covers API and documentation now; if a human UI is introduced, test applicable requirements (existing quality target: WCAG 2.2 AA). Phase 13 does not submit an application or claim registration/certification. Do not weaken privacy or safety to satisfy open-data or content criteria. Optional model support does not make the core solution an AI DPG; a future nomination must classify the nominated artifact and meet current AI-system criteria without exposing sensitive data.

## 12. Local-only acceptance architecture

All Phase 13 acceptance is repeatable offline after dependencies are available, using local Docker/Compose, disposable PostgreSQL/PostGIS, local Keycloak, local TLS, fake secret providers, synthetic load and local build/artifact evidence. No public healthcare or cloud endpoint, external vulnerability target, public registry publication, DPG service, production secret or external account is a test dependency.

Future architecture checks prove production profiles reject unsafe defaults, configuration/secret values do not leak, no external provider is required, management surfaces are protected, health semantics are scoped, application domains remain independent of operational adapters, and no new authority store is introduced.

## 13. Acceptance gate

Phase 13 passes only when all applicable criteria have reproducible evidence:

1. Both deployment profiles and their required/optional components, operator responsibilities, failure domains and portability constraints are documented.
2. Each tested deployment profile has declared SLO/SLI, RPO/RTO and a measured synthetic workload baseline; no unsupported guarantee is claimed.
3. A full isolated synthetic PostgreSQL restore and representative workflow/invariant verification meets the declared RPO/RTO, or the target is recorded as failed and the phase does not pass.
4. Upgrade and interrupted-migration recovery are exercised; readiness and mutation remain blocked for an unsafe schema state.
5. Two replicas/duplicate workers prove durable claims prevent duplicate business effects; graceful shutdown leaves unfinished work recoverable.
6. Security matrix, management-plane checks, safe configuration tests, authorization/resource-rate tests and fake-provider secret/key-rotation tests pass.
7. Release artifact has validated SPDX 3.0 SBOM, digest, verifiable provenance/signature evidence and consumer instructions, without an unsupported SLSA/security claim.
8. Vulnerability, base-image update, incident, alert and retention policies and runbooks are complete with accountable owners and expiry paths.
9. DPG and production gap matrices cite concrete repository evidence, distinguish governance/deployment gaps, and make no registration/certification/legal-conformance claim.
10. All acceptance is local and synthetic; no external system is contacted or mutated.

## 14. Future architecture enforcement

Add structural/configuration checks for: no production default secret; no trust-all TLS or wildcard production CORS; no unprotected management endpoint; no dev profile/Keycloak export in production profile; no management diagnostic exposing secret values; no dependency on a mandatory cloud, broker, cache or orchestrator; no direct public endpoint in tests; no PHI/high-cardinality identifiers in telemetry; and one controlled migration/worker coordination path. Do not add these tests in the design phase.

## 15. Deployment decisions, not design blockers

Operators choose topology, IdP and secret provider, measured SLO/RPO/RTO targets, traffic envelope, retention durations, log/metric backend, rate-limit thresholds and incident contacts. Project governance must decide license, ownership, vulnerability contact, maintainer/contributor processes and whether/how to seek DPG recognition. These choices do not justify weakening a frozen safety boundary.

## 16. Source basis

Versioned facts and primary links are recorded in [Volume V production/security/supply-chain research](../research/volume-5-production-security-supply-chain-research.md) and [DPG Standard research](../research/volume-5-dpg-standard-research.md), retrieved 2026-10-20. Recheck current official versions at implementation and release gates. This specification is not an attestation.
