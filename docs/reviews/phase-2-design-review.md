# Phase 2 Architecture Consistency Review

**Review date:** 2026-10-08  
**Scope:** Documentation-only Phase 2 Platform Kernel, Identity & Trust design. No Phase 2 implementation was started.

## Review outcome

The initial semantic consistency review and the independent master-gate review are recorded separately below. The first pass found 0 BLOCKER / 0 MAJOR / 0 MINOR / 0 DOCUMENTATION findings open after its own corrections. The subsequent independent design/spec review found **1 BLOCKER, 3 MAJOR, 2 MINOR, and 0 DOCUMENTATION findings**; all six have been remediated in the canonical specification/architecture/ADR set. The current open-question register has **0 decisions that must be resolved before Phase 2 implementation**.

This review does not claim that Phase 2 behavior has been implemented or verified. It audits architecture and implementation contracts only. The local reference baseline is Spring Boot 4.1.1 / managed Spring Security 7.1.1 and Keycloak 26.8.0 based on the dated official-source preflight; image provenance and dependency compatibility must be rechecked immediately before implementation use. Deployment-specific provider choice, role mapping, retention, token-revocation window, and measured production reliability targets remain governance decisions under the linked open-question registers.

## Cross-document semantic checks

| Check | Result | Evidence / conclusion |
| --- | --- | --- |
| AyuVahak remains a coordination platform, not an EHR/HIS, HIE replacement, DHIS2/LMIS clone, patient identity master, dispatch ERP, or general BI platform. | PASS | `CONTEXT.md`, the constitution, bounded contexts, and data ownership continue to assign clinical records, patient identity, transport dispatch, public-health indicators, and commodity stock to their authorities. Phase 2 adds no business workflow. |
| Authentication identity is distinct from patient identity and application authorization. | PASS | OIDC subject is issuer-scoped; machine identity is separate; grants are AyuVahak-owned and checked with explicit validated organization/facility context. No token alone grants business access. |
| Identity & Access and Audit have a clear classification. | PASS | They are documented as platform bounded contexts; provenance, event contracts, outbox, idempotency, and consumer dedup are capabilities or owner-local mechanisms, not healthcare business contexts. |
| Receiving-side authority remains functional rather than tied to one job title. | PASS | Authorization maps deployment roles/teams/processes to Clinical Suitability, Operational Readiness, and Acceptance functions. Missing grants deny; no universal title is introduced. |
| Audit is distinct from events, provenance, and logs. | PASS | Protected mutation audit is atomic with that mutation; sensitive-read/security records use a separate durable path. Normal logs and event payloads remain minimized. |
| Event and outbox design is bounded. | PASS | Events/outbox target internal AyuVahak consumers in Phase 2; no global order or exactly-once claim; required per-aggregate order uses monotonic sequence; external exchange remains in the owning context adapter. |
| Command idempotency is separate from consumer deduplication. | PASS | Each is owned by the operation/consumer whose effect it protects. No global shared receipt store or raw payload archive is introduced. |
| Database and module ownership remain consistent. | PASS | One PostgreSQL database remains the deployment baseline; logical schemas belong to owners; Phase 2 may persist its own grant/audit/delivery capability state but creates no fake business schema or cross-context data API. |
| Failure behavior is explicit. | PASS | Authentication/authorization uncertainty denies; mutation audit failure rolls back; sensitive reads wait for durable audit; outbox failure preserves committed facts and recoverable intent; failed consumer effects remain retryable. |
| Interoperability remains domain-owned. | PASS | No universal integration gateway is added. The Phase 2 event framework does not directly publish to external systems; adapters remain context-owned. |
| Phase boundaries remain intact. | PASS | Phase 1 remains frozen at the engineering substrate. Phase 2 design covers kernel, identity, and trust; later business contexts remain not implemented. No Phase 3 or client-flow choice is smuggled into this design. |
| Safety policy remains active. | PASS | No remote mutation, public healthcare test target, credentials, or real patient data is needed. Future tests are constrained to local containers, synthetic fixtures, and controlled stubs. |

## Documentation findings closed during design review

| ID | Classification | Affected files | Issue and consequence | Resolution | Status |
| --- | --- | --- | --- | --- | --- |
| P2-DOC-01 | DOCUMENTATION | `docs/architecture/bounded-contexts.md` | The earlier context diagram colored Identity & Access and Audit as healthcare business contexts, obscuring platform ownership. | Reclassified and redrew the context map with explicit platform bounded contexts and capabilities. | RESOLVED |
| P2-DOC-02 | MAJOR | `docs/architecture/phase-2-platform-kernel.md`, `docs/specs/phase-2-platform-kernel-spec.md`, `docs/architecture/bounded-contexts.md`, `docs/architecture/data-ownership.md`, `docs/architecture/quality-attributes.md`, `docs/adr/0007-domain-events-and-transactional-outbox.md` | Existing wording could let a shared outbox become a generic external publisher/gateway, conflicting with domain-owned adapters. | Clarified that Phase 2 event/outbox delivery is internal; partner exchange stays behind the consuming context's adapter and reconciliation policy. | RESOLVED |
| P2-DOC-03 | MAJOR | `docs/architecture/system-context.md`, `docs/architecture/security-and-trust.md`, `docs/architecture/data-ownership.md`, `docs/adr/0005-external-identity-and-local-authorization.md` | A bidirectional IdP boundary and vague subject/session persistence could imply per-request introspection or token/session storage. | Specified client token acquisition, local signed access-token validation using trusted issuer/audience/key/time configuration, issuer-scoped subject attribution, and no token/session persistence. | RESOLVED |
| P2-DOC-04 | MAJOR | `docs/architecture/phase-2-platform-kernel.md`, `docs/specs/phase-2-platform-kernel-spec.md`, `docs/adr/0012-audit-capture-and-failure-policy.md` | “Durable audit” alone did not distinguish a protected state mutation from a successful sensitive read or a denial when storage fails. | Defined atomic mutation audit, pre-disclosure durable audit for sensitive reads, and a separate fail-closed path for non-mutating security outcomes. | RESOLVED |
| P2-DOC-05 | MAJOR | `docs/architecture/phase-2-platform-kernel.md`, `docs/specs/phase-2-platform-kernel-spec.md`, `docs/adr/0007-domain-events-and-transactional-outbox.md`, `docs/adr/0013-idempotency-and-consumer-deduplication.md` | Generic “deduplicate” language could conflate retrying commands with duplicate event delivery or imply global exactly-once behavior. | Split ownership, durable state, failure tests, and retry semantics between command idempotency and consumer dedup; documented at-least-once delivery and per-aggregate order. | RESOLVED |
| P2-DOC-06 | DOCUMENTATION | `docs/reviews/phase-0-open-questions.md` | The former Phase 0 P2 identity questions still appeared open after the design inputs were settled. | Marked OIDC/Keycloak reference and the RBAC-first functional-authority model resolved; deployment-specific mappings remain configurable. | RESOLVED |
| P2-DOC-07 | MAJOR | `docs/architecture/phase-2-platform-kernel.md`, `docs/specs/phase-2-platform-kernel-spec.md` | The first draft separated command idempotency and consumer dedup into separate work modules while combining core primitives with execution context, so the requested ten-module plan did not have a standalone execution-context module or explicit tenth recovery module. | Restructured the plan to the ten master-gate modules. Command and consumer protection remain distinct within one combined work module; kernel failure/recovery verification is explicitly the tenth cross-cutting module. | RESOLVED |

## Independent master-gate design/spec review

The two-axis review ran against `d6aee6e9816df0cb76312e2d9df4be890aaaf0f4...HEAD`, with standards and specification axes reviewed independently. Findings were resolved before ticket generation.

| ID | Classification | Affected files | Issue and consequence | Resolution | Status |
| --- | --- | --- | --- | --- | --- |
| P2-AUDIT-01 | BLOCKER | `docs/specs/phase-2-platform-kernel-spec.md` | Bootstrap created only `AccessGrantAdministration`, while a vague “grant ceiling” appeared to prevent delegation of `AuditReview` and `EventDeliveryOperations`; implementers could choose materially different privilege models. | The three Phase 2 platform authorities are explicitly enumerated. An administrator may assign/revoke only those authorities for other principals at `PLATFORM` scope; self-grant and self-modification are prohibited. | RESOLVED |
| P2-AUDIT-02 | MAJOR | `docs/specs/phase-2-platform-kernel-spec.md` | An event with no handler was previously considered delivered, so a later-compatible consumer could never recover it. | No-handler events remain visible as `UNHANDLED`, are retained without auto-retry, block later ordered events for that aggregate, and can be replayed only after a compatible handler is registered. | RESOLVED |
| P2-AUDIT-03 | MAJOR | `docs/specs/phase-2-platform-kernel-spec.md` | Organization/facility scope semantics were required but only platform authorities had a production API, leaving test behavior open to incompatible interpretation. | Exact `PLATFORM` / `ORGANIZATION` / `FACILITY` matching rules are stated. Tests seed synthetic grant rows in the real `identity_access` schema and use a deterministic reference-only scope catalog, with no production business context or authority. | RESOLVED |
| P2-AUDIT-04 | MAJOR | `docs/architecture/phase-2-platform-kernel.md`, `docs/specs/phase-2-platform-kernel-spec.md` | The architecture prohibited public APIs from conceptual contracts, while the spec proposed concrete platform routes without recording an explicit decision or rationale. | The spec now explicitly approves only minimal grant-administration, audit-review, and event-recovery routes, with their operational rationale; the architecture links to that bounded decision. | RESOLVED |
| P2-AUDIT-05 | MINOR | `docs/adr/0014-one-shot-local-grant-bootstrap.md`, `docs/architecture/phase-2-platform-kernel.md` | An operator-supplied bootstrap identifier could be mistaken for verified human authentication. | The ADR now calls it an operator-supplied reference, captures OS/DB principals when available, and states the host/shell/database access trust assumption and absence of independent or cryptographic attestation. | RESOLVED |
| P2-AUDIT-06 | MINOR | `docs/specs/phase-2-platform-kernel-spec.md` | Initial lease, retry, and idempotency wait values lacked workload evidence and could be mistaken for production targets. | Values are labeled bounded initial implementation defaults, configuration is validated, tests cover defaults and valid overrides, and no production SLO is claimed. | RESOLVED |

## Scope and evidence limits

- This is a semantic architecture review, not an implementation security assessment or penetration test.
- No local Keycloak, token, outbox, audit, or idempotency behavior was executed; those are Phase 2 implementation seams.
- The design does not claim production compliance, retention duration, availability, latency, capacity, or recovery objectives without a jurisdiction, operating model, and evidence.
- No Phase 2 behavior has been implemented at the time of this design review. The master execution gate authorizes local ticket generation only after the design/spec review passes; no external issue tracker is used.

## Phase 2 design gate

**PASS for Phase 2 design/spec gate.** The architecture and implementation-contract blockers and majors are resolved, and the open-question register has zero items that must precede Phase 2. This does not claim Phase 2 implementation acceptance; the master gate may now proceed to local tickets and implementation with current-source version/provenance checks before dependency use.
