# Phase 0 Open Architecture Questions

This is a register of genuinely unresolved architecture or deployment-governance questions. Product boundaries, domain terms, interoperability placement, ownership principles, Phase 1 engineering baselines, and the generic Phase 2 identity/authorization architecture are settled in the constitution and linked decisions. Deployment-specific provider configuration and role mappings remain governed configuration, not global architecture questions. Patch-version pinning and other reversible implementation details belong to their implementation phase, not this list.

## Decision timing summary

- **MUST RESOLVE BEFORE PHASE 1: 0.** No remaining question changes the settled product boundary, system-of-record model, business context map, interoperability strategy, trust model, or initial engineering baseline.
- **CAN RESOLVE DURING PHASE 1: 0.** Phase 1 does not implement authentication or authorization.
- **CAN RESOLVE DURING PHASE 2: 0.** The Phase 2 identity and authorization architecture is settled in [Phase 2 platform-kernel architecture](../architecture/phase-2-platform-kernel.md), its [specification](../specs/phase-2-platform-kernel-spec.md), and ADRs 0005, 0012, and 0013. Exact provider patch/configuration is verified before the corresponding adapter is implemented; deployment mappings remain configurable.
- **DEFERRED TO LATER VOLUME: 5.** These require a target jurisdiction, a partner contract, measured deployment evidence, or an explicitly approved later capability.

## Resolved by Phase 2 architecture design

### P2-01 — Initial identity-provider protocol and reference product — RESOLVED

- **Decision:** Use OIDC as the human authentication protocol and Keycloak as the local reference provider. Deployments may use a compatible external provider. AyuVahak is an API resource server; it validates signed access tokens locally against configured issuer, audience, trusted signing keys, and time bounds. Human-client login flow remains outside Phase 2. Machine clients use separately scoped client credentials.
- **Why:** This fixes the resource-server and trust contract without imposing a production IdP or client architecture. OIDC ID Tokens are not accepted as API access tokens.
- **Implementation verification:** Before implementing the authentication adapter, select/pin a supported Keycloak reference image and verify selected deployment-provider compatibility, claim contract, signing-key rotation behavior, and Spring Security support using official sources and local-only tests.
- **Owner / decision point:** Phase 2 identity/security implementation owner; concrete deployment provider is selected by the accountable deployment identity administrator.
- **Reference:** [Phase 2 architecture](../architecture/phase-2-platform-kernel.md), [identity research](../research/phase-2-identity-provider-research.md), and [ADR 0005](../adr/0005-external-identity-and-local-authorization.md).

### P2-02 — Generic authorization model and deployment role-to-authority mapping — RESOLVED

- **Decision:** Phase 2 implements AyuVahak-owned, scoped, RBAC-first authorization over functional authorities. Each action is evaluated with an authenticated human or machine actor, explicit validated organization and optional facility, action, target/context, and purpose where relevant. Preserve a path to ABAC without requiring a general policy engine. Do not rely solely on IdP role claims.
- **Deployment configuration:** Each accountable deployment maps its local roles/teams/processes to functional authorities, including Clinical Suitability, Operational Readiness, and Acceptance. Job titles are not global. A missing or unverified grant denies; deployment-specific mappings need not be frozen globally.
- **Owner / decision point:** Phase 2 identity/security owner owns the generic model; deployment administrators and clinical governance own local mappings and must configure them before live authorization.
- **Reference:** [Phase 2 architecture](../architecture/phase-2-platform-kernel.md), [security and trust](../architecture/security-and-trust.md), and [ADR 0011](../adr/0011-functional-receiving-authorities.md).

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

