---
status: accepted
---

**Amended:** 2026-10-08. An event contract has an owning context, stable `<context>.<fact>.v<major>` name, correlation/causation metadata, occurrence and recorded times, and an explicit compatibility policy. Breaking meaning or shape requires a new major event version and planned consumer transition. No distributed event platform or payload schema is selected. This remains an accepted architecture decision for Phase 2 and later; Phase 1 does not implement domain events, an outbox, or idempotency. Phase 1 engineering scope is summarized in [Phase 1 Engineering Decisions](../architecture/phase-1-engineering-decisions.md).

# Use domain events and an outbox without event sourcing

Contexts may publish domain events when other contexts need to react to committed facts. The publishing context owns the meaning and version of its event. Use a transactional outbox where a committed action must be reliably published to an asynchronous consumer or external system. Keep current owned state and an appropriate audit history; do not adopt full event sourcing. Use CQRS only for a demonstrated query need and do not start with distributed CQRS or a mandatory Kafka cluster. This provides reliable integration without making every workflow depend on a distributed event platform.
