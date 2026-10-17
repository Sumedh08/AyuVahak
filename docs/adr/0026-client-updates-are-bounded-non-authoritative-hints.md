---
status: accepted
date: 2026-10-17
---

# ADR 0026: Client updates are bounded non-authoritative hints

## Context

Phase 11 needs timely operational awareness, reconnect behavior and safe degradation. The frozen Phase 2 outbox is internal, at-least-once and only per-aggregate ordered where required. Its event identifiers do not define a global client cursor, and exposing full domain events would leak data and confuse delivery with business truth.

## Decision

Use optional server-to-client SSE hints plus authorized conditional polling/full refresh. Hints carry only an opaque resource reference, owner version, bounded scope sequence and safe event type. The current owner API remains the authority. A rebuildable platform notification projection may provide bounded cursor replay, while expired cursors require full refresh. Bound each client queue/replay and coalesce or disconnect slow consumers. Reauthorize subscriptions on creation, reconnect and resource fetch, and expire long-lived connections within a bounded authorization interval.

The existing outbox remains the durable internal event substrate. PostgreSQL `LISTEN`/`NOTIFY`, if used, is only a post-commit worker wake-up; it is not a replay source. Do not add Kafka, RabbitMQ, NATS, Redis, WebSocket or event-sourcing infrastructure by default. Reconsider a broker only against measured SLO/backlog evidence after outbox tuning and an identified durable fan-out need.

## Consequences

- Notification loss affects freshness, not business correctness.
- Replay is bounded by a scoped cursor window; clients refresh when the cursor expires.
- Notification payloads do not contain PatientReference, clinical facts, full aggregates or tokens.
- Owner changes remain independent of client connections and notification worker health.

## Alternatives considered

- WebSocket was rejected because the approved use is predominantly server-to-client and no bidirectional persistent command requirement exists.
- Exposing Phase 2 outbox events directly was rejected because it leaks internal contracts and does not provide the required scoped cursor semantics.
- A mandatory broker was rejected because current load evidence does not establish need and the existing outbox is adequate for durable internal delivery.

## References

- [Volume IV architecture](../architecture/volume-4-resilient-operations-federation-public-health.md)
- [Phase 11 specification](../specs/phase-11-real-time-resilience-federation-spec.md)
- [Phase 2 platform kernel](../architecture/phase-2-platform-kernel.md)
- [ADR 0007 — Transactional outbox](0007-domain-events-and-transactional-outbox.md)
- [Official research](../research/volume-4-technical-research.md)
