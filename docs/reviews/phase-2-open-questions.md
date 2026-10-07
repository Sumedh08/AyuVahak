# Phase 2 Platform-Kernel Open Questions

**Review date:** 2026-10-08  
**Purpose:** Track only decisions that remain genuinely unresolved. Implementation tasks and deployment configuration are not architecture questions.

## Decision timing summary

- **MUST RESOLVE BEFORE PHASE 2 IMPLEMENTATION: 0.** The identity provider boundary, local reference provider, principal classification, grant bootstrap path, generic authorization model, audit failure behavior, event/outbox contract, retry/ordering behavior, and owner-local idempotency rules are settled in the [Phase 2 architecture](../architecture/phase-2-platform-kernel.md), [specification](../specs/phase-2-platform-kernel-spec.md), and ADRs 0005, 0007, 0012, 0013, and 0014.
- **CAN RESOLVE DURING PHASE 2 IMPLEMENTATION: 0 unresolved architecture questions.** The implementation must recheck the Boot-managed Spring Security dependency and the local Keycloak 26.8.0 image tag/digest against official sources immediately before use. This is a freshness/provenance verification gate, not an open provider-choice question. Exact provider claims and behavior are fixed by the spec; implementation may not silently relax them.
- **DEFERRED TO LATER VOLUME / DEPLOYMENT: 6 governance decisions.** These are intentionally deployment- or later-volume-specific and do not block Phase 2.

## MUST RESOLVE BEFORE PHASE 2 IMPLEMENTATION

None.

## CAN RESOLVE DURING PHASE 2 IMPLEMENTATION

None. The following are settled inputs, not questions:

- The protocol is OIDC/OAuth-compatible signed access tokens; Keycloak 26.8.0 is the local reference image, while deployments may use a compatible provider. Recheck stable image provenance and managed Spring Security compatibility before dependency resolution and authentication implementation.
- Authentication is local JWT validation against explicit trusted issuer, audience, algorithm, key and time constraints. Human principals use issuer plus subject; service principals use a separately configured issuer-scoped client identity. Provider roles/groups do not become AyuVahak grants.
- The first `AccessGrantAdministration` grant is provisioned by the one-shot local non-web command in [ADR 0014](../adr/0014-one-shot-local-grant-bootstrap.md), selected by the project owner. No HTTP bootstrap route or default application administrator exists.
- AyuVahak owns the generic RBAC-first functional-authority grant model. The Phase 2 authority catalog and `PLATFORM` scope are defined in the spec. First-deployment role/team-to-authority mappings are deployment-configurable; a deployment must configure and review those mappings before enabling its corresponding action. They do not need to be globally frozen for all deployments.
- Mutation audit is atomic; sensitive reads and non-mutating security outcomes use the distinct durable path and HTTP behavior defined by the specification.
- Events use explicit UTF-8 JSON envelopes, are delivered at least once, preserve required per-aggregate order, and use the bounded retry/lease/replay behavior in the specification. Command idempotency and consumer deduplication retain different owners.

## DEFERRED TO LATER VOLUME OR DEPLOYMENT GOVERNANCE

| Question | Why unresolved | Impact | Owner / decision point | Latest safe time |
| --- | --- | --- | --- | --- |
| Jurisdictional privacy, legal basis, and retention (Phase 0 L-01) | No target jurisdiction or live deployment is selected. | Determines lawful purpose, retention/deletion, controller/operator responsibilities, and audit handling. | Accountable deployment authority, privacy/legal counsel, and records-governance owner. | Before real patient data or live deployment. |
| Partner interoperability profiles (Phase 0 L-02) | No integration partner is selected. | Constrains later adapters, including patient context and external exchange contracts. | Integration owner with partner/source, security, and clinical/domain owners. | Before enabling each partner exchange. |
| Capacity authority and reservation policy (Phase 0 L-03) | Authority, freshness, and operational meanings vary by deployment. | Determines later Capacity/Transfer policy and stale-data behavior. | Receiving-facility operations authority and coordination owner. | Before capacity-based ranking or live reservation. |
| Measured operational targets (Phase 0 L-04) | Representative workload and support model are unknown. | Avoids invented performance, availability, RPO/RTO, and resource promises. | Deployment operator and clinical/operational risk owner, informed by measurement. | Before a production service commitment. |
| Offline reconciliation and federation (Phase 0 L-05) | No jurisdictional federation or disconnected-write governance is selected. | Determines partition conflict, exchange authority, and synchronization policy. | Later architecture owner and participating health authorities. | Before offline write synchronization or federation. |
| Deployment token-revocation window | Local JWT validation does not perform per-request introspection, so IdP-side revocation may wait for token expiry or a trust/key change. | Defines the residual access window and emergency response for compromised identities/keys. AyuVahak-local grant revocation still denies subsequent protected actions. | Deployment security authority with identity-provider administrator. | Before live use of human or machine tokens. |

No item in the deferred section authorizes a Phase 2 implementation shortcut. They are explicit later governance gates, not unresolved kernel architecture.
