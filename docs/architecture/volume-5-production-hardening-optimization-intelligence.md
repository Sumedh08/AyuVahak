# Volume V — Production Hardening, Optimization and Intelligence

**Status:** Proposed design; pending independent review and Volume V design freeze. This document adds no runtime behavior and does not amend Volumes I–IV.

Volume V addresses two distinct needs: Phase 13 makes the existing modular care-coordination platform safer to adopt and operate; Phase 14 measures deterministic performance opportunities and defines a tightly governed, optional advisory capability. The frozen owner contexts and the PostgreSQL source-of-truth model remain unchanged.

## Product boundary

AyuVahak remains a care-coordination platform. Volume V does not turn it into a cloud platform, EHR, HIE, data warehouse, generic analytics product, MLOps or agent platform, clinical decision-support engine, or autonomous dispatch system. Production hardening is a set of verifiable operating capabilities and evidence, not an infrastructure rewrite. Digital Public Goods (DPG) readiness is an evidence-based assessment, not registration, certification, or a claim of legal compliance.

```text
owner PostgreSQL state      = operational truth
backup/replica/cache        = recovery or performance mechanism, never new authority
measured optimization      = implementation change with semantic-equivalence evidence
advisory model output      = optional, labeled, untrusted interpretation
```

## Phase 13 — deployable and recoverable product

### Supported deployment profiles

| Profile | Supported shape | Required evidence | Not implied |
|---|---|---|---|
| A — single-site | One or more containerized application processes as locally justified; PostgreSQL/PostGIS; compatible OIDC provider; TLS reverse proxy; independent backup target; operator-selected monitoring | Configuration and upgrade procedure; protected management surface; a successful isolated restore drill; declared SLO, RPO and RTO | Kubernetes, a cloud account, a second application replica, or a universal availability guarantee |
| B — highly available institution | Multiple stateless application replicas behind a load balancer; resilient PostgreSQL topology; available identity service; external secret provider; centralized telemetry; tested backups and worker coordination | Multi-replica duplicate-worker and restart evidence; database failover/recovery evidence; measured SLO/RPO/RTO; documented operating ownership and runbooks | Microservices, shared in-memory correctness state, Kafka, Redis, or a particular orchestrator |

Compose remains the local development and acceptance path. Kubernetes is an optional operator choice, never a product prerequisite. A deployment may document another portable topology if it meets the same contracts. No cloud or database vendor is mandatory.

### Availability and capacity

Each deployment selects objectives from these classes and declares target, measurement window, service scope, exclusions, alert threshold, accountable owner, and response: synchronous API availability/latency; required dependency/database health; durable audit/outbox/notification lag; reconciliation, federation and exchange age; Analytics/DHIS2 backlog and unresolved-outcome age; and recovery objectives. A deployment may prioritize or omit a class only with a documented rationale. No universal percentage, emergency-grade SLA, or error-budget process is asserted. The initial SLI catalogue is API availability and latency by route class; PostgreSQL connectivity and pool saturation; durable audit-write failures; outbox/projection lag; reconciliation/exchange/federation age; SSE connection/overflow counts; and Analytics/DHIS2 backlog or unresolved-outcome age. Use low-cardinality labels and omit person, case, transfer, patient, and remote-resource identifiers.

The benchmark contract uses generated synthetic workloads and declares organizations, facilities, active referrals, transfers/day, capacity observations/minute, concurrent SSE clients, federation peers, indicators/periods, and export operations. It records p50/p95/p99 latency, throughput, errors, CPU/memory, DB connection/pool behavior, query counts/plans, outbox/backlog age, and test environment. A baseline is recorded before any optimization; thresholds are deployment profile inputs until a measured operating envelope is approved. Benchmarks make no “enterprise scale” claim.

### PostgreSQL recovery and upgrades

PostgreSQL/PostGIS remains the system of record. Each deployment selects an RPO/RTO class and documents how its topology and operators meet it. A replica can improve availability but is not an independent backup. Backup plans identify data and configuration coverage, encryption/access, off-host separation, retention, WAL/base-backup integrity where PITR is selected, archive-lag/disk monitoring, and recovery owner. Secret material is restored through its provider, never copied into database backups by convenience.

Acceptance requires a restore drill into an isolated disposable database using synthetic state. The drill starts the application at a supported migration version and checks schema/migration integrity, representative owner reads and invariant-bearing workflows, and measured recovery time/data-loss boundary against the deployment's declared RPO/RTO. A configured job or green backup status alone is not proof of recovery.

Upgrades require a verified pre-upgrade backup, a supported source/target version path, migration compatibility and ordering, an explicit single migrator before new replicas accept traffic, health/readiness gates, and a failure/forward-recovery plan. Do not promise automatic down-migrations. Prefer additive expand/contract steps when rolling versions coexist; block rollout if an incompatible migration cannot be safely sequenced. Recovery from failed migration is an operator-owned restore or forward fix, not silent partial startup.

### Runtime, security and secrets

Application replicas are stateless with respect to correctness. Durable work, claims, cursors, idempotency and outbox state remain in their existing owner/platform persistence. Multi-replica worker acceptance runs duplicate instances against the same deployment database and proves claim/fencing/idempotency prevents duplicate business effects. Shutdown stops admission, bounds in-flight work, closes SSE, releases resources, and leaves unfinished durable operations recoverable.

The production security baseline maps applicable, version-qualified requirements from OWASP ASVS 5.0.0 to tests and evidence, and uses OWASP API Security Top 10 (2023) as a threat prompt rather than a compliance score. Existing identity, grant, audit, Phase 9 endpoint/TLS/SSRF, and Phase 11 trust boundaries remain authoritative. Management endpoints are minimized and protected/isolated. No production default secret, trust-all TLS, wildcard CORS, or unauthenticated administration is allowed. Readiness reports required local dependencies; liveness remains process-local and avoids restarting the service solely because an optional peer is unavailable.

Rate limits are layered by route cost and authenticated actor/service identity, organization/scope, partner, and operation class. Network edge controls may also limit source addresses, but IP alone is not an authorization identity. Separate anonymous/bootstrap, authenticated reads, sensitive writes, administration, SSE, federation ingress, and exchange limits. A limit never grants access. Thresholds derive from resource budgets and deployment configuration; rejection is bounded, observable and does not disclose sensitive state.

Application configuration stores administrator-controlled secret references, not secret bytes. A provider-neutral secret-resolution port permits deployment-selected external providers or a controlled local fake. Production profiles fail closed when required secrets cannot be resolved. Rotation supports overlap/roll-forward without database surgery; caches are short-lived and invalidated on revocation where supported. This covers OIDC/JWKS, TLS certificates, federation and Phase 9 credentials, and future release-signing identity. No provider URL is caller-supplied.

### Supply chain, support and governance

Use NIST SSDF v1.1 final as a practice map; track SSDF v1.2 only as a draft until NIST publishes a final revision. Scope OWASP ASVS 5.0.0 by actual API surface. Dependency review records inventory, license, maintenance and vulnerability status; triage combines severity, exposure/reachability, exploit evidence (including CISA KEV as one signal), fix availability and deployment context. Exceptions require an owner, rationale, compensating control, expiry/review date and audit trail. “No known CVEs” is not a security claim.

The initial release evidence profile selects one SPDX 3.0 SBOM per distributable artifact, with generator/profile, coverage, validation, component identities/versions and hashes documented. Require artifact digests and machine-verifiable build provenance, plus signed release assets/manifest and verification instructions. SLSA v1.2 supplies the provenance vocabulary; no SLSA level is claimed until a real builder and verifier demonstrate every requirement. Signing uses an interoperable identity policy; Sigstore is a candidate, not a mandatory vendor/service. OCI digests are immutable identities, so a documented base Java/runtime/container update cadence and emergency patch path must accompany digest pinning.

Required operational artifacts include runbooks for database loss/restore, IdP outage, secret/certificate rotation or compromise, external exchange/federation unknown outcomes, stuck outbox, audit persistence failure, notification degradation, disk/connection exhaustion, failed migration, suspected breach, and vulnerable dependency response. Alerts distinguish immediate security/data-correctness risks from service degradation/backlog and expected business rejections. Logs, metrics, traces and immutable/restricted audit have distinct purposes, access and retention. A retention matrix names owner, purpose, access, configurable period, expiry/deletion or pseudonymization behavior, and any audit/history constraint; the platform invents no jurisdiction-wide retention rule.

The repository currently lacks a root software license, a public project ownership/maintainer statement, privacy/legal notice, code-of-conduct/safeguarding route, vulnerability-disclosure contact, general non-PII portability guide, and consolidated standards/accessibility evidence register. Phase 13 requires these to be assigned, assessed and evidenced; the project owner must make licensing/ownership/governance choices. Document accessibility and localization evidence must cover current API/docs surfaces; if a human-facing UI is introduced, evaluate it against the applicable baseline (the existing quality target names WCAG 2.2 AA) before claiming accessibility. No license, legal basis, controller, DPG application, or certification is inferred by this architecture.

## Phase 14 — measurement and advisory intelligence

### Deterministic optimization track

Optimization starts only after a representative benchmark establishes a bottleneck. Matching remains deterministic, policy-versioned and advisory; Directory, Capacity, Referral, Transfer, Transport, authorization, privacy, federation and disclosure semantics cannot change as a side effect of performance work. The primary candidate areas are candidate generation/geo filtering, owner evidence loading, query plans/indexes/pagination, bounded batch reads, worker contention, SSE fan-out and Analytics computations, but none is presumed to be a bottleneck. Each proposed change records baseline, expected resource effect, changed path, golden synthetic corpus, before/after result equivalence, and rollback. No speculative cache, partitioning, denormalization, Redis, broker, OLAP store, or machine-ranked Matching.

Any later cache must be bounded and reconstructable with an explicit TTL/freshness and invalidation contract. Cache loss is safe, stale data cannot satisfy readiness, and a cache never creates owner truth. A useful performance result includes workload/profile and p50/p95/p99, throughput, resource use, DB queries/plans, error rates and semantic equivalence.

### Intelligence decision and authority

No new intelligence bounded context is introduced by design. If enabled later, a small optional technical/advisory port belongs at the application boundary with provider adapters outside domain code. It cannot read owner persistence directly or invoke owner mutations. `Intelligence=DISABLED` is a complete supported mode; model failure cannot block ordinary coordination.

**Only narrowly approved initial use case:** optional documentation/support assistance over versioned, project-approved non-sensitive documentation and runbooks. Its initial request is a finite allowlisted documentation-topic/question key, not free-form user text. It is not clinical or operational advice. Answers are labeled generated/advisory and include document/source locators; unsupported answers abstain. A no-model documentation/search path remains available. A deployment may keep the capability disabled. This use case cannot receive user case text or use a model to act on a configuration, endpoint, command or business decision.

**Deferred pending a separate use-case review:** non-clinical coordinator summaries, aggregate public-health trend narration, operational anomaly triage, Matching explanations, and hypothetical what-if assistance. These remain deferred because their input scopes, measurable benefit, error costs, fairness or meaningful human review need their own evidence. Canonical structured Matching evidence continues to explain deterministic results.

**Prohibited in Phase 14:** diagnosis, treatment, triage, patient risk/outcome prediction, clinical suitability, autonomous destination/ranking, Capacity readiness, acceptance, reservation, Transfer/Transport state, dispatch, reroute, cancellation, identity/grants, authorization, audit, privacy suppression, DHIS2 corrections, federation trust, or PatientReference disclosure. No patient/clinical input, production-interaction training, model-selected URL, SQL/shell/tool execution, healthcare mutation, or AI override of deterministic policy is permitted.

### Data, provider and result boundaries

Default inputs are approved public/project documentation only. If a future approved non-clinical use case requests structured operational or aggregate inputs, its versioned capability record must identify data classes, user authorization, minimal fields, purpose and retention; PatientReference, IPS, names, diagnoses, narratives, attachments, credentials and secrets remain excluded unless separately authorized by a future design (not this one). Externally supplied text is untrusted and may contain prompt injection. Prompt wording, retrieval, fine-tuning or output filtering is not a security boundary.

The provider port is vendor-neutral and caller-independent. Administrators configure an allowlisted provider/model profile, purpose, endpoint, allowed data classes, retention/training-use terms, jurisdiction constraints when required, credentials by secret reference and version. Callers cannot supply a model URL. Acceptance uses a deterministic fake; no Internet inference or external model is required. The model has no tools or authority to choose network destinations, SQL, shell commands, healthcare APIs, or owner mutations.

Any displayed result is explicitly distinguished from owner facts and deterministic policy. Structured output is validated against a strict schema, allowed values and length bounds; invalid, stale, unsupported or unsafe output is rejected. A capability sheet versions intended/prohibited use, provider/model, prompt/template and policy versions, input fingerprint/source revision, generation time, output validation/abstention state, evaluation evidence, limitations, oversight, monitoring, incident owner, retention and disablement procedure. Raw prompt/output retention is off by default. Nondeterministic output is not claimed exactly reproducible.

### Evaluation, oversight and lifecycle

Before activation, each use case has a baseline (no model and, where appropriate, deterministic heuristic), intended users and decision, prohibited use, synthetic/approved non-sensitive evaluation corpus, acceptance and harm metrics, known limitations, human review/rejection flow, abstention criteria, monitoring, owner and disablement/incident procedure. Metrics are use-case-specific: source-supported factuality, unsupported-claim rate, omission/harmful output, prompt-injection/leakage behavior, abstention, latency/cost/availability and reviewer ability to reject; no generic accuracy/confidence or fairness score is treated as safety evidence.

Any model-assisted feature that could influence a coordination choice requires shadow/advisory evaluation before a separately governed activation decision. The user can inspect the deterministic source, distinguish advisory output, reject it and continue the baseline workflow. Failure, timeout, malformed output, changed model/template, stale input, or unavailable provider causes abstention or no-model fallback. A model version, prompt/configuration, source distribution, policy, incident or intended-purpose change triggers re-evaluation and accountable approval. A fairness assessment identifies use-case-relevant populations/languages and evidence limits; if meaningful evaluation or limits cannot be established, the use remains disabled/deferred. No universal fairness claim is made.

NIST AI RMF 1.0 and its GenAI profile are risk-management references, not certification. WHO guidance informs health-context autonomy, safety, accountability and equity but does not classify all software as a medical device or create one jurisdiction's law as universal. A future change toward clinical intended use requires a separate jurisdiction-specific legal, privacy, safety and evidence process before design or activation.

## Design review invariants

- production readiness is not Kubernetes deployment; availability is not a microservices rewrite;
- backup configuration is not recovery evidence; replica availability is not backup;
- open source is not DPG recognition, and a DPG gap matrix is not certification;
- SBOM, provenance, signatures and clean vulnerability reports are complementary evidence, not proof of security;
- optimization is not semantics change;
- AI-generated explanation is not canonical decision evidence; confidence is not safety;
- human oversight is meaningful only if visible, informed, rejectable and non-blocking;
- intelligence unavailable means the baseline workflow continues; Phase 14 never receives care-coordination authority.

## Authoritative artifacts

- [Phase 13 specification](../specs/phase-13-production-hardening-dpg-readiness-spec.md)
- [Phase 14 specification](../specs/phase-14-optimization-intelligence-spec.md)
- [Production readiness gap analysis](../reviews/production-readiness-gap-analysis.md)
- [DPG readiness gap analysis](../reviews/dpg-readiness-gap-analysis.md)
- [Volume V threat model](volume-5-threat-model.md)
- Official source registers: [production/security/supply chain](../research/volume-5-production-security-supply-chain-research.md), [DPG Standard](../research/volume-5-dpg-standard-research.md), and [AI governance](../research/volume-5-ai-governance-research.md).

This document authorizes no implementation, deployment, registration, external system use or model integration. Each future phase requires separate human authorization.
