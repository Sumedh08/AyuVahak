# Quality Attribute Scenarios

These scenarios are global architectural constraints, not a single phase's acceptance checklist. A scenario becomes an executable acceptance requirement when the phase implementing its relevant capability begins. Phase 1 verifies only the engineering substrate: build reproducibility, architecture-boundary enforcement, PostgreSQL/PostGIS semantics, configuration validation, API/request/error conventions, safe observability, repeatable local deployment, and CI enforcement. Phase 1 does not verify healthcare-domain behavior or the Phase 2 kernel mechanisms. Later phases activate scenarios for the capabilities they implement; the requirements below remain intact until then.

In particular, Phase 1 cannot yet verify Capacity, Referral, Matching, recommendations, Acceptance, Reservation, Transfer, Transport, authorization decisions, outbox delivery, or idempotent business commands. Phase 2 verifies the core domain primitives and platform-kernel, identity, trust, event, outbox, idempotency, audit, provenance, and recovery capabilities it introduces. Later business phases verify workflow behavior such as stale-capacity semantics, pending/confirmed outcomes, recommendation evidence, reservation conflicts, and transfer reconciliation.

Phase 2 activates the kernel portions of QA-05 (internal publication recovery), QA-06 (event duplicate/sequence handling), QA-08 (audit attribution and failure behavior), QA-09 (authentication, scoped authorization, and machine identity), QA-10 (safe diagnostics during kernel failures), QA-12 (outbox/kernel restart recovery), and QA-14 (boundaries among actual kernel modules). It uses synthetic test-only owners and consumers where no business context exists. Later workflow phases separately verify Acceptance, Reservation, stale capacity, external partner delivery/reconciliation, and business-level duplicate outcomes; Phase 2 evidence must not be presented as proof of those later behaviors.

These scenarios are product-level acceptance conditions, not service-level promises. Priority labels are: **CRITICAL** (safety, authorization, or data-integrity invariant), **HIGH** (required platform quality before live deployment), **MEDIUM** (needed when the corresponding capability is selected), and **FUTURE** (retained as a design constraint for a later volume).

No national-scale traffic, uptime, latency, recovery, or storage target is asserted without deployment evidence. Where a number is not currently defensible, the scenario identifies the evidence required before a target is set.

## Safety and information integrity

### QA-01 — Stale capacity cannot appear current

- **Concern:** Evidence freshness and safe interpretation.
- **Scenario:** A source sends a capacity observation that has passed the deployment's validity window, or its source is unreachable when a referral is evaluated.
- **Expected behavior:** The observation is marked stale/unknown, excluded from the feasible set, and never displayed or used as positive availability. A manual confirmation is separately attributed and timestamped.
- **Architectural implication:** Capacity owns source, unit, observed/received time, validity, and freshness classification. Matching consumes that explicit status; it cannot infer availability from absence or zero/false defaults.
- **Verification approach:** Domain and integration checks with synthetic fresh, stale, missing, future-dated, and unavailable-source fixtures; inspect every recommendation input and rendered status.
- **Priority:** CRITICAL.
- **Quantitative target / evidence:** Zero stale or missing observations treated as current availability. Every used capacity observation carries source and observation time; this is a domain invariant, not a throughput target.

### QA-02 — Recommendation remains advisory and explainable

- **Concern:** Safety, transparency, and decision accountability.
- **Scenario:** A referring authority asks for candidate destinations while directory, capacity, capability, and optional travel evidence differ in freshness and completeness.
- **Expected behavior:** The recommendation exposes eligible and excluded candidates, each reason, evidence source and freshness, policy/rule version, and ranking rationale. It does not claim guaranteed suitability or accept/reserve a destination. A human with the required local authority decides.
- **Architectural implication:** Matching owns a versioned deterministic explanation; facts remain owned by Directory and Capacity; recommendation is a time-specific snapshot. No LLM/ML participates in safety-critical eligibility, destination, capacity/readiness, acceptance, reservation, or dispatch decisions.
- **Verification approach:** Deterministic fixtures for inclusion, exclusion, unknown evidence, policy-version change, and evidence invalidation; compare the explanation to the inputs actually used.
- **Priority:** CRITICAL.
- **Quantitative target / evidence:** 100% of recommendations expose the required explanation fields. Zero recommendation is represented as a guaranteed clinical outcome or commitment.

### QA-03 — Missing information cannot become a positive fact

- **Concern:** Safe defaults and data quality.
- **Scenario:** An integration omits a field, returns an unsupported code, is unavailable, or supplies a value with no source timestamp.
- **Expected behavior:** The fact is rejected, marked unknown, or routed for explicit human confirmation under policy. Missing does not become zero, false, “available,” or “accepted.”
- **Architectural implication:** Domain contracts distinguish unknown, absent, negative, and positive. Adapters validate and preserve source status instead of silently coercing values.
- **Verification approach:** Contract tests with omitted, malformed, unknown-code, and partial payloads; review mapping rules for lossy defaults.
- **Priority:** CRITICAL.
- **Quantitative target / evidence:** Zero implicit-positive coercions for safety-relevant facts. Verify with a finite partner-contract fixture set before enabling each adapter.

### QA-04 — External failure cannot fabricate availability or acceptance

- **Concern:** Failure isolation and truthfulness.
- **Scenario:** Capacity, identity, reservation, routing, EMS, or HIE calls time out or return an error during a live coordination case.
- **Expected behavior:** The system retains the last confirmed state with freshness/connection status, marks the attempted action pending or failed, and never fabricates positive availability, delivery, Acceptance, Reservation, dispatch, Arrival, or Handover. The core case remains usable where the failed capability is not required for that action.
- **Architectural implication:** Each domain context owns its port and business failure/reconciliation rules. Transport retry support is shared infrastructure; a failed optional integration is not a global workflow transaction.
- **Verification approach:** Local mock/stub fault injection for timeout, rejection, malformed response, and recovery; verify state and user-visible outcome for each dependency.
- **Priority:** CRITICAL.
- **Quantitative target / evidence:** Zero fabricated positive business outcomes. Service-level latency and failure thresholds are TBD after partner and operating-environment measurements.

### QA-05 — A committed action survives failed internal publication

- **Concern:** Transactional reliability and event recovery.
- **Scenario:** A receiving authority's Acceptance commits in AyuVahak, then internal event delivery or a downstream notification attempt fails.
- **Expected behavior:** Acceptance remains committed; internal publication remains durably recoverable and traceable; retry does not create another Acceptance; notification delivery failure does not imply that the recipient acted. External system delivery remains owned by the relevant context adapter and has its own delivery/reconciliation outcome.
- **Architectural implication:** A transactionally recorded outbox intent is used for asynchronous internal consumers. Events identify the committed fact and its version; consumers handle duplicate delivery idempotently. The shared outbox is not a universal external integration gateway and no distributed transaction is assumed.
- **Verification approach:** Persistence integration tests that fail internal publication after commit, restart processing, replay duplicates, and reconcile status using local containers and synthetic events; later business phases verify adapter-specific external delivery semantics.
- **Priority:** CRITICAL.
- **Quantitative target / evidence:** One business outcome per idempotency identity; every committed event requiring publication has a recoverable delivery record. Delivery latency target is TBD from the partner and workflow deadline.

### QA-06 — Duplicate, delayed, and out-of-order facts preserve chronology

- **Concern:** Message ordering and reconciliation.
- **Scenario:** A duplicate acceptance/reservation update, late arrival report, or older capacity message arrives after a newer event.
- **Expected behavior:** A duplicate does not create a second outcome or active reservation. Both event time and receipt time remain visible. Late factual reports can correct chronology through an attributable correction; an older state update does not silently regress current state.
- **Architectural implication:** Contexts own idempotency and reconciliation semantics; common correlation/retry helpers do not decide business truth. Network delivery is not modeled as a distributed transaction.
- **Verification approach:** Local contract and persistence tests with replay, delayed delivery, out-of-order timestamps, and conflicting facts.
- **Priority:** CRITICAL.
- **Quantitative target / evidence:** Zero duplicate Acceptance outcomes and at most one selected active reservation per Referral. Exact clock-skew tolerance is TBD with deployment time-source evidence.

## Privacy, security, and audit

### QA-07 — Patient context stays purpose-limited

- **Concern:** Data minimization and retention.
- **Scenario:** Referral, receiving, transport, and later analytics actors need different views of one active case.
- **Expected behavior:** Each recipient sees only the minimum information needed for their current purpose. No longitudinal chart or general patient warehouse is created; binary clinical attachments are reference-only in the initial baseline. Closed-case clinical context is restricted or removed under local retention policy.
- **Architectural implication:** Patient context is separated from coordination facts; access is case-, organization-, action-, and purpose-scoped. Raw FHIR resources are processed transiently and not archived by default. Retention is category-based and configurable under jurisdictional policy.
- **Verification approach:** Access-matrix review and synthetic authorization tests for each actor; inspect persisted data, exports, caches, and logs for data not needed by the case.
- **Priority:** CRITICAL.
- **Quantitative target / evidence:** Zero persisted longitudinal records, raw resource snapshots, or binary clinical attachments in the initial baseline. Jurisdiction-specific retention periods require legal/operator evidence before deployment.

### QA-08 — Safety- and access-relevant actions are attributable

- **Concern:** Accountability and auditability.
- **Scenario:** A reviewer needs to establish who viewed or changed patient context, who made each receiving assessment, who accepted/reserved, and what outcome occurred.
- **Expected behavior:** The record identifies actor or machine identity, organization/context, action, target reference, timestamp, outcome, and correlation identifier. Event time and recorded time are distinguishable where reports may be delayed.
- **Architectural implication:** Audit facts are emitted at context-owned action points and captured durably. Audit access is itself attributable. Audit stores references and decision evidence, not entire clinical payloads.
- **Verification approach:** Synthetic end-to-end audit scenarios across success, denial, correction, out-of-band reconciliation, and privileged configuration; assert each required attribution field.
- **Priority:** CRITICAL.
- **Quantitative target / evidence:** 100% of safety- and access-relevant actions have all required attribution fields. Any workflow that cannot meet this target needs a documented safe fallback before production.

### QA-09 — Authentication, authorization, and machine trust stay separate

- **Concern:** Least privilege and identity boundaries.
- **Scenario:** An authenticated user, partner system, or adapter requests access to a case or operation outside its configured organization, role, purpose, or machine scope.
- **Expected behavior:** Authentication is verified at the configured identity boundary; application authorization independently denies the ungranted action. Machine identities are scoped, attributable, and revocable. Secrets are supplied by deployment-managed secret handling. No home-grown password authentication or cryptography is introduced.
- **Architectural implication:** Identity & Access owns local policy and a provider port. External IdP/SMART tokens are evidence for identity or FHIR access, not blanket application authority. Adapters have distinct machine identity/configuration where needed.
- **Verification approach:** Synthetic deny/allow tests for users and machine actors, issuer/audience/expiry failures, revocation, and secret exposure checks.
- **Priority:** CRITICAL.
- **Quantitative target / evidence:** Zero authorization decisions based on authentication alone; zero credentials, tokens, or private keys in source control or normal logs.

### QA-10 — Normal operational logs do not require clinical payloads

- **Concern:** Troubleshooting without PHI exposure.
- **Scenario:** An operator diagnoses an adapter timeout, failed background event, or stale-source incident.
- **Expected behavior:** Logs and metrics identify component, correlation, source/adapter, timing, and outcome without patient narrative, clinical summary, full external payload, credentials, or tokens. Restricted audit access remains separate from ordinary diagnostics.
- **Architectural implication:** Structured operational metadata and correlation are sufficient for routine support. Payload capture is not a debug default; any exceptional evidence capture requires a separately approved, bounded policy.
- **Verification approach:** Synthetic log/metric review and automated secret/payload scanning in future CI; exercise failure diagnosis using only metadata.
- **Priority:** HIGH.
- **Quantitative target / evidence:** Zero PHI/clinical payload or secret fields in normal diagnostic logs. Confirm the exact classification and any legally required audit content with each deployment.

## Availability, recovery, and integration behavior

### QA-11 — Core, integrations, and ancillary services fail independently

- **Concern:** Availability and graceful degradation.
- **Scenario:** AyuVahak's database/application is healthy while an external registry, FHIR endpoint, routing provider, notification gateway, or DHIS2 is unavailable; or an ancillary aggregate export fails.
- **Expected behavior:** Core coordination availability is measured separately from each external integration and ancillary service. Failed optional integrations expose stale/pending status. Notification or aggregate-export failure cannot block a live case; a capability required for a specific safe decision blocks only that decision and prompts the documented fallback.
- **Architectural implication:** Domain-owned ports, bounded timeouts/retries, durable asynchronous delivery where needed, and explicit dependency status. Do not create a hidden all-systems-up transaction.
- **Verification approach:** Local failure injection and recovery checks; later operational dashboards report separate core, integration, and ancillary health.
- **Priority:** HIGH.
- **Quantitative target / evidence:** Availability percentages are TBD. Select them from clinical workflow risk, deployment support hours, network profiles, and measured partner dependency behavior; do not claim national infrastructure availability.

### QA-12 — Backup and background work can be recovered

- **Concern:** Recoverability of coordination state and event delivery.
- **Scenario:** A database or instance is lost, a background publisher stops, or a partner is unavailable across a restart.
- **Expected behavior:** The operator can restore the database, identify the last durable coordination state, resume eligible failed publication, and reconcile partner status without duplicating business outcomes. External synchronization gaps remain visible.
- **Architectural implication:** Document database backup/restore, durable publication intent, retry/dead-letter or manual-reconciliation path, and external correlation. Specific disaster-recovery topology is outside Phase 0.
- **Verification approach:** Later restore drills and controlled replay using disposable local infrastructure, synthetic cases, and partner mocks; verify chronology and idempotency after recovery.
- **Priority:** HIGH.
- **Quantitative target / evidence:** RPO/RTO and maximum queue age are TBD before production. Evidence required: workflow hazard analysis, data-loss tolerance, operator staffing, hosting profile, and partner outage behavior.

### QA-13 — Intermittent connectivity does not imply offline consensus

- **Concern:** Safe operation in low-resource and disconnected environments.
- **Scenario:** A facility loses connectivity after sending a Transfer Request, or a local team makes an urgent decision by phone/radio while AyuVahak is unreachable.
- **Expected behavior:** The digital request remains pending until delivered/confirmed. Local teams use authorized clinical/operational fallback. Later reconciliation records actual event time, recording time, actor/source, and conflicts. Stale reference data is labeled and cannot silently become current.
- **Architectural implication:** Persist local confirmed facts, model pending delivery explicitly, and keep idempotent reconciliation possible. Full offline editing/merge semantics are not assumed or implemented in Phase 0.
- **Verification approach:** Future fault-injection scenarios for partitions, reconnect, delayed duplicates, and human out-of-band reports; do not use a public system as a test target.
- **Priority:** HIGH.
- **Quantitative target / evidence:** No uptime or offline duration target yet. Determine acceptable stale windows and reconciliation service levels from deployment network measurements and clinical policy.

## Maintainability, deployment, and portability

### QA-14 — Business contexts cannot bypass ownership seams

- **Concern:** Modular-monolith integrity and future maintainability.
- **Scenario:** A new module needs another context's facts or an external FHIR/DHIS2/EMS call.
- **Expected behavior:** It uses the owner's published interface/event or its own owner-approved port. It cannot import domain logic from an unrelated module, use another context's repository/internal persistence, or make a direct partner call.
- **Architectural implication:** Package-by-feature contexts, internal/public package discipline, private per-context persistence schemas, no cross-context joins/foreign keys, and executable architecture rules in CI.
- **Verification approach:** ArchUnit rules in Phase 1 verify dependencies among implemented production packages. If needed, use the smallest clearly identified fixture under test sources; do not create placeholder production modules. Inspect migration ownership and database permissions as defense in depth when business contexts are implemented. Reconsider Spring Modulith only if its module verification, testing, event publication, documentation, or observability offers concrete value beyond ArchUnit.
- **Priority:** HIGH.
- **Quantitative target / evidence:** Zero disallowed package dependencies and zero direct cross-context table access in CI. Schema permissions are not treated as the only enforcement because the initial application may use one database identity.

### QA-15 — Fresh contributor can run the core without infrastructure sprawl

- **Concern:** Deployability and contributor access.
- **Scenario:** A contributor checks out the repository on a supported workstation and starts the core or runs its database integration checks.
- **Expected behavior:** The baseline uses a JDK, repository build wrapper, and Docker-compatible runtime for container-backed development/integration. Pure unit/architecture checks do not require containers. No Kubernetes, Kafka, service mesh, or proprietary cloud account is required. Node is not needed until a frontend phase begins.
- **Architectural implication:** One monorepo and modular monolith; Docker Compose remains first-class; external services are mocked or containerized locally.
- **Verification approach:** A fresh-machine bootstrap guide and later clean-environment smoke run with synthetic configuration; verify unit-only tasks also work without a container daemon.
- **Priority:** HIGH.
- **Quantitative target / evidence:** Exact startup-time and minimum-memory targets are TBD after the first runnable baseline and representative low-resource hardware are identified.

### QA-16 — Database semantics match production persistence

- **Concern:** Persistence correctness and test fidelity.
- **Scenario:** A query depends on PostgreSQL transactions, constraints, JSON/geospatial behavior, or PostGIS operations.
- **Expected behavior:** Integration verification exercises the same PostgreSQL and PostGIS semantics as the selected deployment baseline. H2/SQLite are not substitutes for behavior that differs from PostgreSQL.
- **Architectural implication:** Version-controlled SQL migrations and a real PostgreSQL/PostGIS test environment; use disposable local containers and deterministic fixtures.
- **Verification approach:** Testcontainers or equivalent local disposable PostgreSQL/PostGIS containers in CI; migrations and critical repository behavior run against those containers.
- **Priority:** HIGH.
- **Quantitative target / evidence:** Every persistence behavior depending on PostgreSQL/PostGIS is covered by a real-engine integration check. Container image/version pinning policy is set at Phase 1 bootstrap.

### QA-17 — Performance is measured on the real coordination operations

- **Concern:** Response time and resource use for human coordination.
- **Scenario:** A user creates/updates a referral, discovers candidates, retrieves capacity evidence, produces an explanation, opens a case chronology, or an operator drains pending publication.
- **Expected behavior:** Operations complete within deployment-defined workflow deadlines, expose slow external dependencies separately, and do not hide timeout behind a falsely successful result.
- **Architectural implication:** Measure core time separately from partner latency; identify candidate count, evidence volume, spatial query, audit history, and background queue as workload dimensions. Keep SQL and query ownership explicit.
- **Verification approach:** Establish baseline benchmarks after representative synthetic workload and deployment topology are known; benchmark create/update, candidate discovery, capacity retrieval, recommendation, case/audit views, and event recovery.
- **Priority:** HIGH.
- **Quantitative target / evidence:** TBD before first production rollout. Evidence required: expected concurrent users/referrals, candidate set sizes, geography, retention volume, hardware, network latency, and operator workflow deadlines. No scale numbers are invented here.

### QA-18 — Growth does not force premature distribution

- **Concern:** Scalability and optional future decomposition.
- **Scenario:** Workload grows at one deployment, a read-heavy view needs capacity, one adapter has different availability, or jurisdictions choose independent federation.
- **Expected behavior:** The system can first scale vertically; add read scaling only with defined consistency; isolate adapter work where measured; extract a business module only when ownership, scale, isolation, or organizational needs justify distributed-operation cost. Federation exchanges governed references and coordination facts without a central clinical warehouse.
- **Architectural implication:** Enforced module interfaces, owned persistence, explicit events, and no direct cross-context table reads enable later extraction. National reach alone is not a reason for microservices.
- **Verification approach:** Review module contracts and data ownership before an extraction; use representative load and failure evidence to justify scaling; verify federated failure/reconciliation separately.
- **Priority:** FUTURE.
- **Quantitative target / evidence:** No national scale target. Reassess from measured per-deployment load, isolation needs, operational staffing, and federation governance.

### QA-19 — Localization is a data and policy concern

- **Concern:** Language, terminology, country policy, and local codes.
- **Scenario:** A deployment uses a different language, local service/capability codes, identifier scheme, time zone, or legal authorization policy.
- **Expected behavior:** Human-facing labels can be localized; source codes and their systems remain identifiable; local mapping and policy versions are traceable; core domain meaning does not depend on one country's registry or terminology.
- **Architectural implication:** Separate display language from coded concept identity; domain contexts define meaning while country/partner adapters map codes; avoid embedding one jurisdiction's roles or authority into universal rules.
- **Verification approach:** Later acceptance with selected target languages and partner terminology sets; review that unmapped codes remain unknown rather than silently equated.
- **Priority:** HIGH.
- **Quantitative target / evidence:** Language count and terminology coverage are TBD with initial jurisdictions and users. Evidence required: target locale list, scripts, terms, code systems, and local policy owners.

### QA-20 — Human-facing workflows meet an accessibility acceptance baseline

- **Concern:** Access for people with disabilities and varied operating conditions.
- **Scenario:** A clinician or coordinator uses a future AyuVahak interface with keyboard input, screen magnification, assistive technology, or a small/mobile display during a time-sensitive workflow.
- **Expected behavior:** The complete workflow is perceivable, operable, understandable, and robust; status is not communicated by color alone; accessible authentication and error handling are included.
- **Architectural implication:** Accessibility is a product acceptance requirement, not a UI implementation choice deferred to visual polish. The future human interface should target WCAG 2.2 AA, while local law may require more.
- **Verification approach:** Combine automated checks with manual keyboard and assistive-technology review on complete workflows; record exceptions and user validation. W3C defines testable criteria and recommends WCAG 2.2 for current accessibility work ([WCAG 2.2](https://www.w3.org/TR/WCAG22/), W3C Recommendation, 2024-12-12; retrieved 2026-10-08).
- **Priority:** FUTURE.
- **Quantitative target / evidence:** Target conformance level is WCAG 2.2 AA for future web workflows. Exact supported devices, languages, assistive technologies, and legal requirements are set with the first deployment/user group.

## Quality-attribute decision gates

Before production, each deployment sets availability, RPO/RTO, retention, stale-data windows, integration deadlines, and performance budgets using local clinical risk, law, infrastructure, partner behavior, and measured workload. Phase 1 verifies only substrate-relevant scenarios and preserves the seams needed for later verification; it does not set unsupported national service targets or claim that later-phase domain scenarios have already been verified.

## Volume II phase activation

These scenarios remain global architectural constraints. A scenario becomes an executable acceptance requirement when the phase implementing its relevant capability begins. A phase verifies only the behavior it owns and does not claim that later business behavior has passed. The Phase 1 substrate gate is recorded in the [Phase 1 engineering decisions](phase-1-engineering-decisions.md); Volume II gates are in the five phase specifications.

| Phase | Quality scenarios activated as executable acceptance requirements |
| --- | --- |
| 3 — Directory | Source attribution; active/deactivated history; direct capability assertion; geospatial correctness; bounded deterministic query; no missing location treated as zero; scoped curation and audit; no cross-context table access. |
| 4 — Capacity | Observed/received/valid times; freshness and expiry; source precedence/conflict; unknown vs explicit zero; deterministic projection/rebuild; readiness explanation; concurrent updates; audit/outbox recovery. |
| 5 — Referral | Immutable submitted revision; explicit lifecycle; scope and authority; idempotent retries; audit atomicity; minimum PatientReference; no patient data in logs/events/metrics. |
| 6 — Matching | Hard eligibility; stale/unknown evidence excluded from confirmed rank; deterministic ordered result; structured rationale; policy/evidence versioning; snapshot applicability/invalidation; no patient payload. |
| 7 — Transfer | Distinct assessments/Acceptance/Reservation/Transfer/Arrival/Handover; one active request/transfer invariant; last-capacity-unit race; cancellation/release/reconciliation; duplicate/delayed messages; event recovery; attributable actions. |
| 8 — Healthcare standards & interoperability | Configured R4/profile handling; malformed/unsupported data is explicit failure; version/identifier conflicts do not merge records; mCSD partial/failed sync cannot deactivate valid Directory facts; IPS disclosure is participant- and purpose-authorized; raw FHIR/IPS remains transient; unknown terminology does not become positive evidence; Schedule/Slot never implies Capacity. |
| 9 — Secure external exchange | Endpoint allowlisting and DNS/redirect/SSRF defense; TLS/token/key rotation and revocation; bounded payload/pages/time; duplicate/replay separation; bounded retry only when safe; ambiguous remote result remains unknown; no exact-once claim; secrets and patient data absent from telemetry. |
| 10 — Emergency transport coordination | Assignment/ETA do not imply movement; explicit evidence required for Transfer `IN_TRANSIT` and Arrival; duplicate/out-of-order provider statuses do not regress state; cancellation/reroute preserves history; manual mode works without EMS; no patient identity/clinical payload/location history in Transport telemetry or generic events. |

Phase 3 tests use real PostgreSQL/PostGIS for spatial semantics. Phases 4–7 add context-owned state and local failure tests incrementally. Phases 8–10 add only local mock/fixture external-contract tests, with provider/profile versions pinned per acceptance run. Performance targets remain evidence-gated: benchmark Directory geographic search, Capacity current/readiness query, Referral queues, Matching generation, Transfer incoming/active queues, bounded FHIR/partner response handling, and Transport status ingestion under measured representative synthetic load before setting service budgets. No national throughput target is implied. None of these scenarios requires an external healthcare endpoint.
