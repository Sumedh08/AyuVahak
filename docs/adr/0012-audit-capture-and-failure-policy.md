---
status: accepted
date: 2026-10-08
---

# Preserve accountability across mutation and non-mutating actions

## Context

AyuVahak must be able to attribute safety- and access-relevant actions without placing patient context in ordinary logs. A single asynchronous audit mechanism cannot guarantee that a protected state change has its required evidence: the process may commit the state and fail before queuing a log. Conversely, a mutation that is committed before its required audit record is durable would be falsely presented as accountable. Sensitive reads and denials do not necessarily have a protected business mutation with which to share a transaction, so they need a distinct durability rule.

## Decision

For a protected state mutation, the required minimized audit record commits in the same PostgreSQL transaction as the protected state change. If audit capture fails, both roll back; no successful mutation is reported.

For a successful sensitive read, the required audit record must be durably accepted before protected content is disclosed. Denials and other non-mutating security outcomes use a separate durable audit path. If this path is unavailable, the system fails closed: it does not disclose protected content, does not turn a denial into an allow, and does not claim an audit record was captured. It surfaces the operational failure without leaking protected information.

Audit records identify actor (human or machine), organization and optional facility context, action, target reference, event/recorded time, outcome, and correlation; purpose is included where the operation requires it. The record is minimized and does not contain full clinical payloads. Audit is distinct from operational logging, provenance, and domain events. Retention, correction, export, and tamper-evidence are deployment-governed; no jurisdiction-free duration is set.

## Consequences

- A state mutation cannot succeed when its required audit evidence cannot commit.
- The read/denial path needs an explicit, separately durable behavior and observable outage handling.
- Audit APIs must accept structured, minimized facts rather than arbitrary request/response bodies.
- The owning context submits audit facts through a boundary; it does not write Audit persistence directly.
- Tests must inject failure before and after the relevant persistence boundary using local disposable PostgreSQL and synthetic actions.
- Audit storage or operations may not be silently treated as ordinary logs or as a central clinical record.

See [Phase 2 architecture](../architecture/phase-2-platform-kernel.md), [Phase 2 specification](../specs/phase-2-platform-kernel-spec.md), and [security and trust](../architecture/security-and-trust.md).
