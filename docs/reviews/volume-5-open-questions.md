# Volume V Design Question Disposition

**Purpose:** Capture resolved architecture questions and distinguish implementation blockers from deployment choices and future governance. This is not a Phase 13/14 ticket list.

## Must resolve before implementation

**None.** The specs contain safe defaults and explicit evidence gates. Implementation can choose ordinary library/configuration details only after the separately authorized phase begins. A concrete new contradiction must be recorded and resolved without weakening ownership, privacy, security, or optionality.

## Resolved design questions

| ID | Question | Resolution |
|---|---|---|
| P13-Q1 | Which production profiles are supported? | Portable single-site and highly available institutional profiles; local Compose is a development/acceptance topology, not production HA. |
| P13-Q2 | Is Kubernetes required? | No. It is optional if an operator chooses it and can satisfy the same contracts. |
| P13-Q3 | What SLI/SLO model applies? | A common SLI catalogue plus deployment-declared target, window, scope, alert and response; no universal numeric SLO. |
| P13-Q4 | How are RPO/RTO selected? | Deployment declares measurable values and topology. Acceptance measures restored data-loss boundary and recovery time against them. |
| P13-Q5 | What proves backup/recovery? | Isolated synthetic restore, startup on supported migration version, invariant/workflow checks and measured RPO/RTO; backup status alone is insufficient. |
| P13-Q6 | How are replicas/workers coordinated? | Existing durable owner/platform claims, outbox, idempotency and database fencing; no in-memory correctness state. Test duplicate workers. |
| P13-Q7 | What secret abstraction? | Provider-neutral resolution port, reference-only application config, external provider in production, fail-closed required-secret behavior and rotation. |
| P13-Q8 | What rate-limit boundary? | Layer by route class, authenticated principal/service, organization/scope, peer/partner and operation cost. IP is supplementary, never authorization. |
| P13-Q9 | SBOM format? | SPDX 3.0 as one initial canonical profile; implementation verifies generator/tool compatibility. |
| P13-Q10 | Provenance/signing proportion? | Digest + machine-verifiable provenance + signed asset/manifest and verifier guidance. SLSA v1.2 is vocabulary; no level claim before evidence. Sigstore is a candidate, not mandatory. |
| P13-Q11 | Vulnerability policy? | Contextual triage using severity, reachability/exposure, exploit evidence including KEV, fix availability and deployment context; time-bounded exceptions. |
| P13-Q12 | Release/version policy? | Phase 13 documents stable artifact/version identity and compatibility support; no immutable release numbering scheme is guessed beyond repository governance. |
| P13-Q13 | Required runbooks? | Database, IdP, secrets/certificates, exchange/federation unknown outcome, outbox, audit, notification, exhaustion, migration, breach and dependency incident. |
| P13-Q14 | Retention model? | Owner/category matrix with purpose, access, configurable duration, expiry/deletion/pseudonymization and history constraints; no universal duration. |
| P13-Q15–18 | What is current DPG basis, status and external evidence? | Official public basis checked 2026-10-20; numbered release/current page/2025 AI update/2026 guide version discrepancy preserved. Matrix reports 0 satisfied, 5 partial, 2 not satisfied, 4 requiring governance evidence. No certification or submission. |
| P14-Q19–20 | What optimization/performance baseline? | Candidate areas only; no target presumed. Synthetic declared workload and measured baseline before change, plus golden-corpus semantic equivalence. |
| P14-Q21 | Which intelligence use cases are approved? | Only a narrow documentation/support assistance evaluation over approved versioned non-sensitive documentation. Coordinator summaries, aggregate narratives, anomaly triage, Matching explanation, and what-if simulation are deferred. Clinical/autonomous authority is prohibited. |
| P14-Q22 | New bounded context? | No. If separately authorized, use a narrow advisory application port and provider adapter outside owner domains. |
| P14-Q23–24 | Allowed data? Are patient/clinical inputs permitted? | Approved documentation only for the initial use case. PatientReference, IPS, names, diagnosis, narrative and operational case data are excluded. |
| P14-Q25–26 | Versioning/evaluation? | Version capability, provider/model, template/policy/corpus and input fingerprint. Evaluation is use-case-specific against no-model baseline with support, omission, harmful output, injection/privacy, abstention, latency and reviewer measures. |
| P14-Q27–29 | Fallback, abstention, human review? | Ordinary documentation search; abstain on unsupported/stale/unsafe/invalid/unavailable. Advisory labels/source locators, informed rejection, no hidden mutation, non-blocking baseline. |
| P14-Q30–32 | Shadow, provenance, change review? | Any material choice-influencing use must first run shadow/advisory. Store bounded provenance, not raw prompts by default; changes to model/provider/prompt/corpus/purpose/data/incident trigger evaluation and approval. |
| P14-Q33 | Fairness? | Use-case-specific groups/languages, corpus coverage, error differences, limitations and accountable thresholds; if evidence is inadequate, defer/disable rather than assert fairness. |
| P14-Q34–35 | Prompt injection and tools? | Treat external text as untrusted; no SQL/shell/network/tool/owner mutation execution. Deterministic access and output validation are outside model. |
| P14-Q36 | How disable all intelligence? | `Intelligence=DISABLED` is a complete mode; no provider/model startup dependency and ordinary product workflows continue. |

## Deployment configuration

- Selected production profile and actual topology, including optional platform/orchestrator.
- Organization-specific SLOs, RPO/RTO, measured traffic envelope and capacity margins.
- Identity provider, secret provider, TLS/key rotation, ingress and monitoring endpoints.
- Rate-limit thresholds, alert routing, incident contacts, log/audit/backup retention.
- Deployment privacy/legal roles, purposes, jurisdiction, retention schedule and federation terms.
- Indicator/reporting configuration remains as frozen in Volume IV; it is outside Phase 13/14 policy changes.
- Intelligence stays disabled unless a future authorized implementation has a reviewed provider/model, data policy, capability sheet, evaluation and governance activation.

## Governance / external action

- Rights holder chooses a software license and establishes redistribution/contributor provenance.
- Accountable project owner and maintainers publish ownership/governance evidence.
- Publish a privacy/legal notice, vulnerability-disclosure contact, contributor conduct/complaint path, and any applicable content/harassment/underage-safety determination.
- DPG submission/registration and any external validation or certification.
- A release-signing identity and production artifact hosting trust policy.
- Deployment-specific legal/privacy classification if intended purpose or input scope changes.
- External penetration test or production security assessment, if separately authorized.

## Future volume

- Any feature outside the single bounded documentation assistance candidate, including clinical or patient-level intelligence, predictive public health, ML ranking, autonomous workflows, model training/fine-tuning, AI tools/agents, and unapproved LLM/RAG platforms.
- Any new phase after Phase 14, and any architecture change to the frozen care-coordination or interoperability ownership boundaries.
