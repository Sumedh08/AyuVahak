# Architecture Decision Records

ADRs record durable AyuVahak architecture decisions, their context, and the rationale that later maintainers need. The current constitution is a concise index; detailed decisions and domain facts live in their linked documents.

## Status and change policy

- **Accepted** means the decision is the current baseline unless a later ADR says otherwise.
- **Superseded** means a later decision replaces all or part of the historical decision. Keep the old record and link the replacement; do not erase decision history.
- **Amended** decisions retain their original ADR number and add a dated amendment note that explains the scope of change and links the current baseline.
- **Rejected** or **withdrawn** proposals remain for context if they were durable enough to record; do not treat them as active requirements.
- A decision is not changed by silently editing prose. For a material change, amend the current ADR or create a new ADR that names the superseded record. Correct factual errors directly only when the decision itself did not change, and record the correction date/source.
- Avoid an ADR for a reversible implementation detail already covered by an engineering baseline. Link the baseline document instead.

## Current decisions

| ADR | Decision | Current status |
| --- | --- | --- |
| [0001](0001-coordination-state-not-clinical-record.md) | Coordination state, not clinical record | Accepted |
| [0002](0002-modular-monolith-and-ports-adapters.md) | Modular monolith and ports/adapters | Accepted; amended 2026-10-08 |
| [0003](0003-java-spring-postgresql-postgis.md) | Java/Spring and PostgreSQL/PostGIS | Accepted; amended 2026-10-08 |
| [0004](0004-fhir-at-interoperability-boundaries.md) | FHIR at interoperability boundaries | Accepted |
| [0005](0005-external-identity-and-local-authorization.md) | External authentication, local authorization | Accepted; amended 2026-10-08 |
| [0006](0006-deterministic-advisory-matching.md) | Deterministic, explainable advisory matching | Accepted |
| [0007](0007-domain-events-and-transactional-outbox.md) | Domain events and transactional outbox | Accepted; amended 2026-10-08 |
| [0008](0008-lightweight-compose-first-deployment.md) | Lightweight Compose-capable deployment | Accepted |
| [0009](0009-separate-receiving-confirmations.md) | Historical fixed-role confirmation decision | Superseded by ADR 0011 |
| [0010](0010-domain-owned-integration-ports.md) | Domain-owned integration ports and adapters | Accepted |
| [0011](0011-functional-receiving-authorities.md) | Receiving authority functions, locally mapped | Accepted; supersedes ADR 0009 |
| [0012](0012-audit-capture-and-failure-policy.md) | Atomic audit for mutations; separate durable path for non-mutating security/access records | Accepted |
| [0013](0013-idempotency-and-consumer-deduplication.md) | Command idempotency and consumer deduplication have separate owners and records | Accepted |
| [0014](0014-one-shot-local-grant-bootstrap.md) | Provision the first AyuVahak grant through a one-shot local operator command | Accepted |
| [0015](0015-capacity-evidence-and-readiness.md) | Capacity is source-attributed, freshness-aware evidence; unknown is not positive | Accepted |
| [0016](0016-deterministic-recommendation-snapshots.md) | Deterministic, policy-versioned advisory recommendation snapshots | Accepted |
| [0017](0017-transfer-journey-and-capacity-reservation.md) | Transfer request attempts and Capacity reservations have separate owners | Accepted |
| [0018](0018-matching-owns-capability-operational-mapping.md) | Matching owns versioned CareCapability-to-CapacityDimension mapping | Accepted |
| [0019](0019-synchronous-recommendation-applicability.md) | Recommendation applicability is synchronously revalidated at use time | Accepted |
| [0020](0020-declared-future-consumer-delivery-state.md) | A declared same-Volume future consumer uses a durable non-replayable waiting state | Accepted |
| [0021](0021-grant-registered-authorities-at-declared-scope.md) | Identity & Access administers registered authorities at their declared scope | Accepted |
| [0022](0022-matching-owner-reads-use-a-dedicated-service-identity.md) | Matching owner reads use a dedicated scoped service identity | Accepted |
| [0023](0023-referral-owned-transient-patient-context-exchange.md) | Referral owns authorized, transient patient-context exchange | Accepted |
| [0024](0024-partner-trust-and-exchange-lifecycle.md) | Shared partner trust enforcement; owner-owned exchange meaning | Accepted |
| [0025](0025-transport-separate-from-transfer.md) | Transport is a separate context from Transfer | Accepted |
| [0026](0026-client-updates-are-bounded-non-authoritative-hints.md) | Client updates are bounded, non-authoritative hints | Accepted |
| [0027](0027-federation-uses-explicit-peers-and-home-authority.md) | Federation uses explicit peers and home authority | Accepted |
| [0028](0028-analytics-uses-versioned-owner-produced-aggregates.md) | Analytics uses versioned owner-produced aggregates | Accepted |
| [0029](0029-dhis2-is-a-downstream-aggregate-export.md) | DHIS2 is a downstream aggregate export | Accepted |

ADR 0005 and ADR 0007 have dated amendments for the Phase 2 identity/token boundary and at-least-once/per-aggregate event delivery semantics. These amendments clarify the existing decisions; they do not change the Phase 1 baseline or begin Phase 2 implementation.

ADRs 0015–0017 constrain the Volume II Capacity, Matching, and Transfer domains. They do not select external adapters, deployment-specific legal policy, universal freshness/ranking values, or implementation artifacts.

ADRs 0018–0020 resolve cross-phase ownership and event-activation semantics for Volume II. They clarify that operational mapping and current recommendation applicability belong to Matching, and narrowly permit a same-Volume future consumer event to remain durably waiting without automatic replay.

ADR 0021 extends the existing Identity & Access grant-administration boundary for later context-owned authorities. It preserves grant ownership, platform-scoped administration, and the Phase 2 trust/audit/idempotency invariants while permitting an explicit target scope validated by the owner of the referenced organization or facility facts.

ADR 0022 separates the human authority to request a recommendation from the machine identity used to read other contexts' facts. It preserves each context's authorization and audit ownership and documents the exact deployment-scoped read grants required by Matching.

ADR 0010 was clarified on 2026-10-15: owner adapters own mapping/operation configuration, while Phase 9 may centralize a narrow technical trust envelope for consistent endpoint and credential enforcement. This does not create a connector registry or alter domain ownership.

ADRs 0023–0025 record the Volume III design decisions: Referral controls and transiently processes optional IPS context; a shared platform capability enforces partner trust without owning exchange semantics; and Transport owns movement coordination separately from Transfer. They freeze design only. Phases 8–10 implementation requires separate authorization.

ADRs 0026–0029 record the frozen Volume IV design decisions: realtime client delivery is a bounded hint channel; federation uses explicit revocable peers and home authority; Analytics consumes owner-produced versioned aggregates; and DHIS2 receives downstream aggregate DataValueSets through Phase 9. Their accepted ADR status records these decisions but does not authorize Phase 11/12 implementation. Volume V remains unauthorized.

