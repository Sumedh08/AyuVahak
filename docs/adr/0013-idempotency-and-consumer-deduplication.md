---
status: accepted
date: 2026-10-08
---

# Keep command retries and event-consumer duplicates separately owned

## Context

Retries arise at different boundaries. A caller can retry a command after losing its response, while an event can be delivered more than once after a publisher or consumer loses an acknowledgement. These have different keys, owners, state transitions, and atomicity requirements. One platform-wide receipt store would expose unrelated business outcomes and invite cross-context coupling.

## Decision

Command idempotency belongs to the context that owns the command outcome. An opted-in command scopes an idempotency key by actor/client, organization, operation, and target context, and compares a stable normalized request fingerprint. An exact retry returns a safe reference to the same committed outcome; reusing a key with different input is a conflict. Concurrent retries converge on one effect. Receipts do not store raw clinical request/response payloads. Retention follows the command's justified retry horizon and deployment policy; there is no universal TTL.

Event-consumer deduplication belongs to each context that owns a consumer effect. That consumer records event identity with its own effect, atomically when the effect is transactional. A committed duplicate is acknowledged without repeating the effect; a failed effect cannot be marked complete. At-least-once delivery is assumed. These consumer receipts do not replace caller command idempotency.

No global exactly-once guarantee or shared business-state/receipt store is introduced. In a modular monolith, the owner remains visible in code and persistence boundaries even when all records share one PostgreSQL database.

## Consequences

- Command and event-consumer failure/replay tests are separate.
- Business contexts introduce their own receipt state with the real command or consumer; Phase 2 tests mechanisms with test-only fixtures and does not scaffold empty production domains.
- Event delivery and command responses must avoid storing or logging raw patient-bearing payloads.
- Event replay, key expiry, fingerprint normalization, and recovery behavior must be documented for each owning operation before it is exposed to callers.

See [Phase 2 architecture](../architecture/phase-2-platform-kernel.md) and [Phase 2 specification](../specs/phase-2-platform-kernel-spec.md).
