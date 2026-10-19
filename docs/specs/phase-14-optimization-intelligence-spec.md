# Phase 14 — Optimization and Intelligence

**Status:** Proposed specification; pending independent review and Volume V design freeze. This document authorizes no implementation, model integration, training, deployment or later phase.

## 1. Objective and two independent tracks

Phase 14 improves measured system efficiency without changing business semantics and may offer narrowly bounded advisory intelligence only where a use-case-specific safety case demonstrates benefit. Deterministic optimization and optional intelligence have separate designs, evidence and acceptance gates. AI is not a substitute for performance work, deterministic Matching, owner policies or public-health governance.

The product remains usable with all intelligence disabled. PostgreSQL owner state and existing deterministic owner policies remain authoritative.

## 2. Scope and non-goals

### Deterministic optimization scope

- Profile representative synthetic workloads before changing query, index, batching, concurrency or caching behavior.
- Evaluate candidate generation/geo filtering, evidence loading, Matching execution, database plans/pagination, worker contention, SSE fan-out and Analytics work only when measurements identify a bottleneck.
- Compare before/after outputs against a frozen synthetic golden corpus and the same policy/version inputs.
- Document measured resource/latency effect, operational trade-offs and rollback.

### Optional intelligence scope

Only the optional, non-clinical documentation/support assistance use case described below may be initially implemented after separate authorization and a passed evaluation contract. A deployment may disable it entirely. It reads only approved, versioned, non-sensitive AyuVahak documentation/runbooks, answers documentation questions with source locators, labels generated content as advisory and abstains where support is absent.

### Explicit non-goals

- No diagnosis, treatment, clinical triage, patient risk/outcome prediction, or clinical decision support.
- No autonomous destination selection/ranking, readiness, Acceptance, reservation, Transfer/Transport state, dispatch, reroute or cancellation.
- No identity/grant, authorization, audit, privacy suppression, DHIS2 correction, federation trust, business policy or disclosure decision by a model.
- No PatientReference, IPS, names, diagnosis, medication, narrative clinical context, attachments, real case data, credentials or secrets as model input.
- No model-selected URL, SQL, shell command, tool/action execution, health-system mutation or owner mutation port.
- No automatic training/fine-tuning from production interactions; no external model mandatory; no mandatory vector database, agent framework or model registry platform.
- No replacement of deterministic Matching; no claim that confidence or generated explanation is a canonical rationale.

## 3. Deterministic optimization contract

Before optimization, capture source revision, runtime/database profile, synthetic data dimensions, warm/cold condition, cache state, hardware, workload mix, repetitions and baseline. Measures include p50/p95/p99 latency, throughput, errors, CPU/memory, connection-pool utilization, DB query count/plans, lock wait, backlog age and resource saturation as applicable.

The benchmark workload declares organizations, facilities, active referrals, transfer volume, capacity observation rate, SSE clients, peer count, Analytics indicators/periods and exports. Report its applicability limits; it makes no generic scale claim.

An optimization acceptance pack contains: bottleneck evidence; exact change; baseline and candidate result; golden-corpus comparison; semantic invariants exercised; resource measurements; known trade-offs; rollback; and observability. The change must preserve authorization, readiness/freshness, recommendation applicability/ranking semantics, reservation/transfer invariants, federation ownership, disclosure/privacy and export ordering. Any mismatch blocks optimization until explained and approved.

Caching is not a default remedy. Any approved cache is bounded, reconstructable, TTL/freshness-aware and has explicit invalidation; a miss or loss is safe. Stale remote Capacity cannot satisfy readiness. No Redis correctness dependency, speculative partitioning/denormalization, broker, Spark/OLAP, or generic cache layer is introduced without a new evidence-based design authorization.

## 4. Intelligence architecture and authority

No Intelligence bounded context is introduced. A capability, if separately activated, is invoked through a narrow application port whose interface expresses a named advisory use case and bounded input schema. Provider-specific/model SDK types remain in an adapter. Domain entities and owner repositories cannot import model types or call inference directly. The adapter cannot access owner persistence directly and exposes no business mutation/tool ports.

Every output is untrusted. It cannot create, update, approve, rank, suppress, reserve, disclose, route or authorize any owner fact. It cannot enter a domain aggregate as a fact. A human may choose a separately authorized normal application action after examining authoritative source records; model output never supplies the authority.

## 5. Approved, deferred and prohibited use cases

### Approved for narrowly bounded initial evaluation

**Documentation/support assistance** over project-approved, versioned documentation and runbooks. The intended user asks how a documented system capability/configuration/operation works. The capability returns a bounded answer with source locators, explicit advisory labeling, an abstention when evidence is insufficient, and an ordinary link/search path. It cannot receive a case-specific question, user-supplied sensitive text, deployment secret/config value, external healthcare content, or command to execute. This is an optional evaluation target, not a promise to ship a model.

### Deferred; needs separate use-case decision and evidence

- Non-clinical coordinator summary of an authorized coordination view.
- Public-health narrative over approved aggregate snapshots.
- Operational anomaly triage beyond deterministic thresholds.
- Generated explanation of Matching candidates (the existing structured deterministic evidence remains canonical).
- Hypothetical what-if simulation.

These have broader data or decision influence and require independently specified input purpose, harm metrics, fairness/representativeness analysis and human workflow. Deferral is not implicit approval.

### Prohibited in Phase 14

All uses in the explicit non-goals above, including clinical and autonomous coordination authority, individual patient prediction, training on operational interactions, unsupervised external content ingestion, model tools and model-directed external exchange.

## 6. Input and provider boundary

Initial approved input is a finite, administrator/versioned set of documentation topic/question keys over approved product docs and runbooks. Do not accept arbitrary free-form user prompt text in the initial use case; unknown/extra fields (including an attempted synthetic patient marker) are rejected before provider invocation. The document/version allowlist and request budget bound retrieval. The capability logs no raw query or response by default and does not persist case/user content. Secrets, credentials, patient/clinical content and operational identifiers are not present in the accepted request schema and are denied by call-site authorization.

Use a provider-neutral port and administrator-controlled, versioned provider profile: provider identity, model/version allowlist, purpose, endpoint, permitted data class, retention/training-use terms, jurisdiction controls where required, secret reference, timeout, rate/cost budget and disable state. No caller-supplied endpoint/model. External providers are optional and require deployment review; initial acceptance uses a deterministic local fake, not external inference. If a local model is later proposed, its artifacts, licenses, provenance, resource/isolation and update policy require review before download or use.

The model receives no tools, function execution, arbitrary retrieval outside the approved corpus, network selection, database access, shell access or application mutation capability. Retrieved/user-provided document text is untrusted. Prompt injection is not considered solved by prompt wording, RAG, fine-tuning or output filtering; enforce deterministic authorization and data boundaries outside the model.

## 7. Output contract, human review and abstention

The documentation answer uses a strict schema with bounded text length, source document/version/section locators, classification (`ADVISORY` or `ABSTAINED`), and a bounded reason code. Validate schema, values, text size, source existence and allowlist before display. Reject invalid schema, ungrounded source references, disallowed content, stale policy/doc version or timeout. Do not expose a confidence score as calibrated safety evidence.

The interface/presentation must distinguish system fact, deterministic policy result and generated interpretation. It displays advisory status and source links visibly, supports rejection/feedback without affecting domain state, and preserves the non-model search/read workflow. No hidden state change occurs before review. Users can proceed without the answer. A model failure, unavailable provider, stale result, unsafe content, weak evidence or change under review means abstain or show unavailable and use ordinary documentation search.

Any future capability that could influence a coordination choice must first run in shadow/advisory mode with no business-state effect, compare to the no-model/deterministic workflow, and pass separate accountable approval before activation.

## 8. Evaluation contract

Each versioned use case declares intended users/purpose, prohibited uses, input/output classes, owner, baseline, synthetic/approved non-sensitive evaluation corpus, measurement method, acceptance threshold, failure/abstention cases, human review, monitoring, residual risk, deployment scope, disablement and incident process.

For documentation assistance, evaluate at least:

- Answer support against the approved source; unsupported-claim and omission rate.
- Correct source/version/section locator and behavior after a document changes.
- Abstention for absent, ambiguous, stale or out-of-scope information.
- Unknown request fields containing a synthetic patient/clinical marker are rejected before provider invocation; prompt-injection attempts in retrieved synthetic documentation do not bypass data or authority boundaries.
- Privacy marker/secret leakage into answer, logs, traces, metrics, audit or durable storage.
- Malformed, oversized, delayed, unavailable and contradictory provider output.
- Reviewer ability to distinguish and reject generated content; no effect on a coordination workflow.
- Latency, availability and resource/cost bounds compared with ordinary search/no-model use.

Do not use a single generic accuracy score. A fairness assessment identifies relevant user/language groups and corpus coverage, outcome/error differences, limitations and an accountable response threshold. If meaningful evidence or criteria are unavailable, the capability remains disabled; do not claim “fair AI.” Model confidence is not a substitute for calibration or safety evidence.

## 9. Provenance, privacy and lifecycle

The capability sheet records use-case key/version, provider/model/version, prompt/template and policy versions, approved source corpus revision, generation time, bounded input fingerprint, source locator set, schema-validation/abstention status, evaluation run and approval, known limits, monitoring/incident owner, retention and disable instructions. Raw prompts/answers are not retained by default. If a deployment needs user feedback, collect only a separately approved minimal signal, without raw sensitive content, and never use it as implicit training data.

Changes to model/provider, model terms, prompt/template, corpus, input classes, intended purpose, policy, data distribution, an incident or a meaningful quality/safety metric trigger re-evaluation and approval before activation. Keep old provenance; do not claim exact replay for nondeterministic inference. A version can be rolled back or the whole capability disabled without affecting core service.

No deployment may activate intelligence until required Phase 13 production gates for secure configuration, secret handling, supply-chain review, monitoring, incident response and rollback have passed for that deployment. NIST AI RMF 1.0/GenAI profile and WHO guidance are lifecycle and ethics references, not certification or universal law. Intended-purpose expansion toward clinical/CDS/medical-device use requires separate jurisdiction-specific review and authorization.

## 10. Monitoring and incident response

Permitted low-cardinality signals: use-case key, provider/model version class, result class, validation failure, abstention, latency, availability and disable state. Do not put query/body, document content, patient/case IDs, generated response, secrets or high-cardinality fingerprints in logs, metrics, trace baggage or ordinary audit. Security-relevant configuration/admin activation and version changes are audited without payloads. Monitor safety/quality against the use-case evaluation contract and disable on defined trigger; do not collect broad user activity for model training.

Incident handling covers suspected data leakage, prompt injection, unsafe/unsupported answer, model/provider compromise, unexpected retention/training use, quality/bias regression and accidental use outside purpose. Contain by disabling the capability/provider profile, preserve bounded provenance, investigate within privacy controls and re-evaluate before reactivation.

## 11. Local acceptance and architecture enforcement

Acceptance uses synthetic docs and a deterministic fake provider on local infrastructure; no external inference endpoint or Internet is required. Test disabled mode; unavailable/timeout/malformed/oversized output; hallucinated unsupported claim; synthetic prompt injection; forbidden patient marker; stale corpus; model/template change; no-side-effect shadow comparison with ordinary documentation search; human rejection; deterministic/no-model fallback; provenance reconstruction; output-label visibility; and proof that no owner mutation path is callable. Shadow evaluation must not retain sensitive prompt/answer bodies or affect coordination state.

Future structural rules prove owner domains do not depend on model SDKs; model/advisory types do not enter owner aggregates; intelligence adapters cannot import owner repositories or mutation ports; no patient/IPS models are reachable; external HTTP is available only through an approved provider port and security boundary; generated text cannot select a URL, SQL, shell or tool; intelligence config defaults disabled; and no later volume package is introduced. Do not implement these checks in the design phase.

Deterministic optimization acceptance uses a golden corpus and explicit before/after result-equivalence checks plus workload/latency/resource evidence. No performance result is a correctness exception.

## 12. Acceptance gate

Phase 14 passes only if:

1. Benchmarks reproduce a documented baseline and every optimization is justified by a measured bottleneck and preserves expected results over the declared corpus.
2. No-intelligence operation is complete; intelligence defaults disabled and cannot make core coordination unavailable.
3. Any enabled documentation assistant has an approved capability sheet and passes its evaluation contract using a local fake only.
4. Provider, prompt, input and output boundaries are deterministic, bounded and administrator-controlled; no sensitive inputs or tools reach the model.
5. Human-visible advisory labels, source evidence, rejection, abstention, fallback and disablement behave as specified.
6. Provenance is adequate to reconstruct configuration/source identity without retaining raw sensitive prompts/outputs or claiming exact generation replay.
7. Threat, privacy, fairness limitations, incident and model-change review are documented; no unsupported fairness, clinical safety or legal claim is made.
8. No model, provider, failure or optimization path changes owner business facts or rules.

## 13. Deployment decisions, not design blockers

Deployment may keep intelligence disabled; if enabled after future authorization, it selects a reviewed provider/profile and jurisdictional controls. Per-use-case metrics and thresholds are approved by an accountable owner. Model versions, data corpus, support languages, retention and incident contacts are deployment configuration only after the applicable evaluation and Phase 13 gates pass.

## 14. Source basis

See [AI governance and health-AI research](../research/volume-5-ai-governance-research.md), retrieved 2026-10-20, for official NIST AI RMF 1.0 and GenAI Profile, WHO health-AI guidance and OWASP LLM01:2025 source links, version facts, limitations and design consequences. Recheck current official status before implementation. This specification is not legal advice, a medical-device determination or a claim of model safety.
