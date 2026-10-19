---
status: proposed
date: 2026-10-20
---

# ADR 0033: Measured optimization preserves domain semantics

## Context

Potential optimization targets span Matching, database access, notification fan-out, federation and Analytics. Anecdotal speed changes can alter ordering, freshness, authorization or disclosure behavior; caches and denormalized data can silently become stale authority.

## Decision

Every optimization is preceded by a reproducible workload baseline and measured bottleneck. Acceptance compares before/after outputs against the same synthetic golden corpus, owner/policy versions and input evidence, in addition to latency, throughput, resource, query and backlog measures. Any semantic difference requires an explicit explanation and cannot be treated as a performance-only change. A future cache must be bounded, reconstructable, freshness-aware, invalidated explicitly and safe to lose; it cannot establish readiness or owner truth.

## Consequences

- Optimization is evidence-led and independently reviewable.
- No speculative Redis, broker, partition, denormalization, warehouse or ML ranking is justified by this ADR.
- A measured result is limited to its declared environment/workload; it does not claim generic scale.

## Alternatives considered

- Optimizing from anecdotes was rejected because improvement and correctness could not be demonstrated.
- Replacing deterministic Matching with a learned ranking model was rejected; performance and decision semantics are separate concerns.
- Treating cache entries as current owner facts was rejected because stale evidence must remain stale/unknown.

## References

- [Volume V architecture](../architecture/volume-5-production-hardening-optimization-intelligence.md)
- [Phase 14 specification](../specs/phase-14-optimization-intelligence-spec.md)
- [Quality attributes](../architecture/quality-attributes.md)
- [ADR 0016 — Deterministic recommendation snapshots](0016-deterministic-recommendation-snapshots.md)
