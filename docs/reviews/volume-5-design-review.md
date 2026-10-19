# Volume V Architecture and Specification Review

**Review date:** 2026-10-20
**Review stage:** Design challenge pass; independent cross-document code-review follows.
**Scope:** Phase 13 production hardening/DPG readiness and Phase 14 optimization/intelligence design only.

## Review basis

The challenge used root `AGENTS.md`, product boundaries, constitution, frozen Volumes I–IV architecture/contracts/state machines/threat and privacy models/acceptance records, Phase 13/14 specifications, research notes and ADRs 0030–0033. Primary-source research is recorded separately with retrieval dates, versions, source links, limitations and AyuVahak consequences. No runtime code, tests, infrastructure, model or external system was used.

## Grill-with-docs challenge and disposition

| Proposal challenged | Disposition and evidence-based rationale |
|---|---|
| “Production-ready means Kubernetes.” | Rejected. Two portable deployment profiles are specified; the single-site profile and local Compose remain viable. Kubernetes is an optional operator choice, not a correctness or acceptance requirement. |
| “HA requires microservices.” | Rejected. A modular monolith can use stateless app replicas and durable DB-backed worker claims. Acceptance tests duplicate workers against owner persistence; no service split is needed to express the recovery contract. |
| “We need Kafka for scale.” | Rejected absent a measured bottleneck and a specific durable fan-out requirement unmet by the transactional outbox. Benchmark first; no broker in the baseline. |
| “Every deployment needs Redis.” | Rejected. PostgreSQL remains correctness authority; caches are optional, reconstructable and safe to lose. No Redis correctness dependency is defined. |
| “DPG-ready means certified/registered.” | Rejected. Internal evidence matrix is a readiness aid only. The DPG version discrepancy is preserved; no submission or official recognition is claimed. |
| “Open source alone makes a DPG.” | Rejected. License and clear ownership are separate criteria; the repository lacks a root license and public ownership statement. Rights-holder/governance work remains. |
| “Backup configured means recovery proven.” | Rejected. Phase 13 requires isolated restore, application startup, migration/invariant/workflow checks, and measured RPO/RTO comparison. A replica is not a backup. |
| “Health endpoint means observability is complete.” | Rejected. The existing minimal actuator checks are one component; Phase 13 adds deployment SLI/SLO, alert, backlog, audit-failure and incident evidence. |
| “Container image means reproducible release.” | Rejected. Release identity requires digests, validated SBOM, provenance/signature and independent verification. No SLSA level is claimed without full evidence. |
| “Latest dependency is safest.” | Rejected. Pin a supported evidence basis and inspect compatibility, maintenance, vulnerability, provenance and update policy; revalidate at implementation/release. |
| “Zero known vulnerabilities means secure.” | Rejected. Inventory and triage combine severity, exploit evidence, reachability, exposure, fix availability and context; unknown flaws and residual risks remain. |
| “One production topology fits all.” | Rejected. Small/single-site and highly available institutional profiles expose different operator capability and measured targets without vendor lock-in. |
| “AI should improve Matching automatically.” | Rejected. Deterministic Matching remains the canonical policy result; no approved model authority or replacement is specified. |
| “LLM can rank hospitals better.” | Rejected. This is high-risk destination authority and outside Phase 14. |
| “Confidence score means calibrated safety.” | Rejected. Use-case evaluation may test calibration only if a score is actually used; it is never safety evidence by itself. |
| “Human in the loop makes AI safe.” | Rejected as a standalone mitigation. Oversight must expose sources/limits, allow rejection, and leave the deterministic workflow available without hidden mutation. |
| “We can train on production interactions later.” | Rejected. No implicit learning; any training requires separate data-governance and design authorization. |
| “An LLM may infer missing Capacity.” | Rejected. Capacity authority and stale/unknown semantics stay with the Capacity owner; no clinical/operational input is allowed in the approved initial use case. |
| “AI may override deterministic rules.” | Rejected. Models cannot call owner mutation ports or change Matching, readiness, acceptance, reservation, authorization or privacy decisions. |
| “An AI explanation is causal evidence.” | Rejected. Generated documentation answers are advisory; deterministic owner evidence remains canonical and a model explanation cannot replace it. |
| “Optimization means weighted scoring.” | Rejected. Optimization is profile-led; Matching semantics and rankings are invariant unless separately designed/authorized. |
| “We need a vector database, agent framework or RAG by default.” | Rejected. The approved evaluation candidate is a bounded documentation assistant; no mandatory retrieval architecture or action framework is selected. A local indexed corpus may be evaluated later without tools or external content. |
| “Predictive public-health analytics should be default.” | Rejected. Phase 12 remains aggregate reporting; predictive or narrative capabilities are deferred and require distinct governance/evaluation. |

## Cross-phase invariant checks

- PostgreSQL remains operational authority; backup, replica, cache, event, federation, Analytics and advisory output do not supersede owner truth.
- Production hardening adds operating evidence and portable deployment contracts, not an architecture rewrite.
- DPG criteria are assessed individually against dated official evidence; project-level governance and local implementation evidence are not conflated.
- Deterministic optimization requires semantic equivalence. Intelligence is disabled by default, advisory-only, non-clinical and unable to invoke tools or mutations.
- Phase 14 activation is gated on applicable Phase 13 secure configuration, supply-chain, monitoring, incident and rollback controls.
- Acceptance remains synthetic/local; no public healthcare system, cloud, vulnerability target, model service or production credential is an acceptance dependency.

## Independent review

The independent Standards and Spec reviews are recorded in the design-review addendum below. Findings are classified and tracked to resolution. Freeze is prohibited while a BLOCKER or MAJOR remains open.

### Independent review addendum

Pending until separate reviewers complete standards and specification passes against this baseline. This section must be updated before the Volume V freeze commit.

## Final classification

Pending independent review and remediation. No implementation may start based on this review alone.
