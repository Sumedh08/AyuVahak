# Phase 0 Open Architecture Questions

This is a register of genuinely unresolved architecture or deployment-governance questions. Product boundaries, domain terms, interoperability placement, ownership principles, Phase 1 engineering baselines, and the generic Phase 2 identity/authorization architecture are settled in the constitution and linked decisions. The provider/protocol and deployment-specific permission mapping below remain Phase 2 decisions. Patch-version pinning and other reversible implementation details belong to the engineering baseline, not this list.

## Decision timing summary

- **MUST RESOLVE BEFORE PHASE 1: 0.** No remaining question changes the settled product boundary, system-of-record model, business context map, interoperability strategy, trust model, or initial engineering baseline.
- **CAN RESOLVE DURING PHASE 1: 0.** Phase 1 does not implement authentication or authorization.
- **CAN RESOLVE DURING PHASE 2: 2.** The identity-provider adapter choice and generic authorization framework are Phase 2 work; deployment-specific role mappings remain configurable by the accountable deployment.
- **DEFERRED TO LATER VOLUME: 5.** These require a target jurisdiction, a partner contract, measured deployment evidence, or an explicitly approved later capability.

## CAN RESOLVE DURING PHASE 2

### P2-01 — Initial identity-provider protocol and product

- **Why unresolved:** The architecture requires an external identity provider but no first deployment or IdP product/protocol has been selected.
- **Impact:** Determines the inbound authentication adapter, issuer/audience validation, claim mapping, and local development identity fixture. It does not change local authorization ownership or the no-home-grown-password rule.
- **Owner / decision point:** Phase 2 identity/security owner with the accountable deployment identity administrator.
- **Latest safe time:** Before implementing the corresponding Phase 2 authentication adapter. Phase 1 uses no application authentication adapter.

### P2-02 — Generic authorization model and deployment role-to-authority mapping

- **Why unresolved:** Phase 2 must implement the generic authorization model, while Clinical Suitability, Operational Readiness, Acceptance, and other functions remain universal domain functions. Actual job titles and delegations are deployment-specific and need not be globally frozen.
- **Impact:** The generic model determines how subject, organization, case, action, and purpose are evaluated. Each deployment's configurable mapping determines who may record each separate assessment or Acceptance. Missing mapping must remain denied/pending, never inferred.
- **Owner / decision point:** Phase 2 identity/security owner defines the generic model with the accountable health authority; deployment administrators and clinical governance configure local mappings.
- **Latest safe time:** Define the generic model during Phase 2 before authorization behavior is enabled. Configure each deployment's mapping before it is used to authorize a real receiving decision. Generic synthetic grants may be used in local development.

## DEFERRED TO LATER VOLUME

### L-01 — Jurisdictional privacy, legal basis, and retention profile

- **Why unresolved:** No target jurisdiction, controller/operator allocation, or deployment has been selected.
- **Impact:** Sets lawful purpose, consent/emergency rules, retention/deletion, breach obligations, audit handling, residency, and federation constraints for patient context and operational records.
- **Owner / decision point:** Accountable deployment authority, privacy/legal counsel, and records-governance owner.
- **Latest safe time:** Before collecting real patient information or operating a live deployment. It is not a Phase 1 code-structure choice.

### L-02 — Partner FHIR profiles, version, terminology, and exchange contract

- **Why unresolved:** No integration partner or target jurisdiction is selected; FHIR support is optional and partner conformance varies.
- **Impact:** Determines whether and how a particular adapter maps FHIR R4/R4B/R5 or another interface, which profile/terminology is accepted, and what patient context crosses the boundary.
- **Owner / decision point:** Integration owner with source-system owner, security authority, and clinical/domain owner.
- **Latest safe time:** Before enabling each partner exchange. No generic connector may assume that “FHIR-compatible” means equivalent semantics.

### L-03 — Facility capacity authority, freshness windows, units, and reservation contract

- **Why unresolved:** Capacity meanings, sources, refresh intervals, reservation semantics, and local operational owners differ by facility and system.
- **Impact:** Determines which observations can be used for a particular deployment's eligibility/readiness policy and when they expire to unknown. No stale or missing value may be interpreted positively while this policy is absent.
- **Owner / decision point:** Receiving facility operations/capacity authority and the health-system coordination owner, with the Capacity/Transfer domain owner.
- **Latest safe time:** Before using capacity to rank or before requesting/representing a confirmed Reservation in a live deployment.

### L-04 — Performance, availability, RPO/RTO, and minimum resource targets

- **Why unresolved:** There is no representative workload, operating-hours model, host profile, support team, or clinical risk envelope from which to set credible numbers.
- **Impact:** Affects deployment sizing, backup/recovery schedule, operational support, and service targets. Invented national-scale values would mislead operators.
- **Owner / decision point:** Deployment operator and clinical/operational risk owner, informed by Phase 1 benchmarks and selected workload.
- **Latest safe time:** Before a production service commitment or production rollout; set local budgets after representative synthetic benchmarks and operational analysis.

### L-05 — Offline reconciliation and cross-deployment federation protocol

- **Why unresolved:** Neither disconnected editing/conflict policy nor a target federation governance model is selected. Different authorities and network conditions will require local agreements.
- **Impact:** Determines permitted provisional actions, conflict resolution, synchronization direction, partition recovery, cross-deployment identity/reference handling, and the role of any regional/national exchange.
- **Owner / decision point:** Later product architecture owner with participating health authorities, privacy/governance owners, and operational users.
- **Latest safe time:** Before implementing offline write synchronization or enabling cross-deployment federation. Current architecture preserves provenance, pending state, idempotency, and reconciliation without promising offline consensus.

