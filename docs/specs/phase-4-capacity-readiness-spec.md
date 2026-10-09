# Phase 4 Specification — Capacity and Operational Readiness

**Volume:** II — Core Care Coordination  
**Phase boundary:** Current operational evidence and readiness; no Referral, Matching, Transfer, or Capacity Reservation implementation.  
**Architecture references:** [Volume II architecture](../architecture/volume-2-core-care-coordination.md), [Capacity/readiness policy v1](../architecture/capacity-readiness-policy-v1.md), [context contracts](../architecture/volume-2-context-contracts.md), [Phase 2 kernel spec](phase-2-platform-kernel-spec.md).

## Objective and outcome

Make the Capacity context independently answer: “What source-attributed operational evidence is currently usable for this facility/resource, how fresh/conflicted is it, and what does the configured deterministic readiness policy conclude?” The system must expose uncertainty. It does not assert a real-world guarantee.

## Scope and exclusions

**Included:** typed Capacity dimensions, immutable Capacity Observations, source/provenance and validity, source-priority/fallback policy, current projection and query-time reconstruction, readiness evaluation, authorized manual/adapter-neutral observation intake, bounded current/history queries, owner schema/migrations, the Phase 2 audit and command-idempotency contracts, temporal/concurrency/failure acceptance. Phase 4 emits no Capacity event and creates no Capacity outbox intent.

**Excluded:** reservations/holds (introduced by Capacity in Phase 7), referral/candidate matching, clinical assessments, staffing/bed/inventory ledger, source-specific FHIR/HIS connector, external polling job, routing, public integrations, or numeric universal freshness/availability promise.

## Domain model and invariants

Capacity owns these conceptual concepts:

- **CapacityDimension**: bounded deployment-managed code with allowed value kind, unit, semantic meaning, reservable flag and readiness use. No untyped JSON.
- **CapacityObservation**: immutable source-attributed assertion for one Facility/dimension and observation time. Quantity/unit and categorical state are distinct variants; negative quantity or incompatible unit is invalid.
- **CapacitySourcePolicy**: versioned per-dimension priority and permitted fallback rules, with authorized owner and effective time.
- **CurrentCapacityEvidence**: rebuildable projection carrying selected observation/source/policy versions and freshness state.
- **ReadinessEvaluation**: deterministic result with required dimensions, outcomes, reason codes and evidence references. It is not the receiving-side Operational Readiness Assessment.

Capacity's readiness contract accepts an explicit required CapacityDimension set and evaluation policy/time. It does not accept CareCapability codes, consume Referral, or infer which dimensions a CareCapability requires. Matching owns that versioned mapping in Phase 6. Missing/unknown dimensions or required readiness policy produce UNKNOWN/UNAVAILABLE; they never yield READY. Phase 4 creates no Capability Operational Requirement Policy and no reservation behavior, even if a dimension is marked reservable for later use.

Required times are `observedAt`, server-side `receivedAt`, and `validUntil` when explicit or derivable. Source identifier is issuer/system scoped; duplicate same ID/same fact is idempotent, same ID/different fact conflicts. Correction or retraction is append/supersede, never destructive update.

Current projection follows [Capacity/readiness policy v1](../architecture/capacity-readiness-policy-v1.md): exclude invalid/superseded/expired evidence; apply configured priority/fallback; select by `observedAt`, not arrival; equal-authority disagreement is `CONFLICTED`. Freshness is checked at query time. Missing policy or evidence yields UNKNOWN/STALE/EXPIRED, never zero or positive. Readiness precedence is explicit NOT_READY, then CONFLICTED, STALE, UNKNOWN, and READY only if every required dimension is current and passing.

## Commands and queries

### Commands

| Command | Contract |
| --- | --- |
| Define/change CapacityDimension | Capacity policy authority; typed schema of meaning/unit/value kind; version changes are audited and do not reinterpret old observations. |
| Configure SourcePriority/FreshnessPolicy | Deployment Capacity policy authority; versioned per dimension; unconfigured dimension remains unusable for positive decisions. |
| Submit CapacityObservation | Authorized facility Capacity reporter/service; include source-scoped observation ID, facility/dimension, typed value, observed time, validity/provenance. REQUIRED owner-local idempotency. |
| Correct/retract CapacityObservation | New attributed fact referencing the superseded observation, reason and event time. REQUIRED idempotency; no hard delete. |
| Reconstruct current projection | Read-only internal Capacity operation; deterministic from immutable evidence and policy at the requested instant; safe to repeat and creates no new source fact or stored projection state. |

Mutation audit for manual updates, corrections, source policy changes and other safety-relevant changes is atomic with the Capacity transaction. Automated sources have service identity and source provenance, not an invented human actor.

### Queries

- `GetCurrentCapacityEvidence(facility, dimensions, at)` returns one result per requested dimension, with value/unit if known, status, reasons, source/provenance reference, policy/projection version and times.
- `EvaluateOperationalReadiness(facility, requiredDimensions, policyContext, evaluationTime)` returns multi-state readiness and dimension-level reasons. It evaluates only the supplied dimension set and does not decide Clinical Suitability or Acceptance.
- `GetCapacityObservationHistory` is restricted, bounded, time-filtered, and exposes only authorized source facts.
- Empty query results and unknown dimensions remain explicit; no omission is semantically interpreted as false or zero.

Phase 4 publishes no Capacity events: it introduces no Reservation fact and no current Capacity event needs asynchronous fan-out. The same-Volume future-consumer event exception is governed by the [cross-context contract](../architecture/volume-2-context-contracts.md); it does not authorize speculative reservation events before Phase 7 creates reservations. Matching reads current Capacity evidence synchronously and owns the capability-to-dimension translation.

### HTTP resource contract

| Method and route | Purpose |
| --- | --- |
| `POST /api/v1/capacity/observations` | Ingest one typed, attributed observation; requires `Idempotency-Key`. |
| `POST /api/v1/capacity/observations/{observationId}/corrections` | Append correction/retraction referencing the source fact; requires `Idempotency-Key`. |
| `GET /api/v1/capacity/facilities/{facilityId}/current` | Bounded current evidence by repeated dimension code, including freshness/provenance result. |
| `POST /api/v1/capacity/readiness-evaluations` | Evaluate a supplied Facility + requirement-dimension set at an explicit/current instant; query operation with no business-state mutation. |
| `GET /api/v1/capacity/facilities/{facilityId}/observations` | Authorized, bounded history with time/source filters. |

Dimension/source policy administration is a separate scoped management command under the same `/api/v1/capacity/policies` namespace and is available only to the configured Capacity policy authority. It is not exposed as arbitrary property editing. Request bodies are typed and reject unknown value kinds/units.

## Persistence, transaction and failure behavior

Capacity owns its logical schema and migration history, created only in Phase 4. An observation write commits the immutable fact with its required audit and idempotency state in a Capacity transaction. Current evidence is derived at query time from immutable observations/corrections and the selected policy inside a repeatable-read Capacity transaction; Phase 4 does not write a mutable projection or emit a business event, so it creates no outbox intent. Reconstruction is deterministic and history remains the source of truth. No Directory tables are joined; Facility references are validated through Directory's application contract. No distributed lock or cross-context transaction is used.

If the source adapter/command path or database is unavailable, no new evidence is recorded. If the source response is delayed, `receivedAt` does not make an old `observedAt` current. If policy is missing, reject unsafe configuration or return UNKNOWN; never choose “latest arrival.” A timeout in a future source integration remains unknown until owner-safe retry/reconciliation.

## Error taxonomy

Stable codes include `CAPACITY_DIMENSION_UNKNOWN`, `CAPACITY_VALUE_INVALID`, `CAPACITY_UNIT_MISMATCH`, `CAPACITY_SOURCE_UNTRUSTED`, `CAPACITY_OBSERVATION_CONFLICT`, `CAPACITY_OBSERVATION_STALE`, `CAPACITY_OBSERVATION_EXPIRED`, `CAPACITY_EVIDENCE_UNKNOWN`, `CAPACITY_READINESS_NOT_READY`, `CAPACITY_READINESS_UNKNOWN`, `CAPACITY_POLICY_UNCONFIGURED`, `CAPACITY_VERSION_CONFLICT`, and `CAPACITY_SCOPE_MISMATCH`. Stale/unknown states are typed domain outcomes; authorization and audit failures use Phase 2 contracts. No raw source payload or SQL is returned.

## Security, audit, provenance and idempotency

Every report command carries a Phase 2 validated organization/facility scope and authorized Capacity-reporting function. Every fact records system/source or actor, observation/receipt time, reporting method, policy version and correction chain. Manual override is a separate privileged action with a reason; it does not silently become top priority. Provider role claims do not authorize the report. Required mutation audit is atomic. Source payloads and patient data are not part of capacity state/logging. Command idempotency is REQUIRED for ingestion/correction, NOT APPLICABLE for read queries; consumer dedup does not apply until a consumer event exists.

## Concurrency and temporal rules

Concurrent observation writes commit immutable facts under Capacity-owned transactions. Current-evidence reads use one repeatable-read snapshot for the facts, dimension definition, and selected policy, so a query does not combine different committed versions. The response's projection version identifies the deterministic projection algorithm; it is not a persisted projection revision. Equal timestamps with equal-authority differing values become conflict; arrival order is never the tie-break. At `validUntil`, evidence is expired. An explicit zero is distinct from no observation. Capacity event time and aggregate sequence are not substituted with wall-clock ordering. No reservation invariant is tested until Phase 7.

## TDD seams and acceptance scenarios

1. With synthetic facts, source priority and configured fallback select expected current evidence; fallback source is explicit.
2. Duplicate source ID/same fact returns same outcome; changed fact conflicts; correction preserves history.
3. Tests prove `observedAt`, `receivedAt`, `validUntil`, freshness boundary, future skew, stale, expired, unknown and superseded behavior independently.
4. Equal-tier disagreement returns `CONFLICTED`; `NOT_READY` outranks missing dimensions; READY requires all current evidence; explicit zero is not missing.
5. PostGIS is used only for Facility references, not healthcare geography logic; test database is actual PostgreSQL/PostGIS and no H2/SQLite substitute is accepted.
6. Query-time reconstruction from immutable evidence yields the same result and policy version; concurrent updates do not hide a committed observation.
7. Real PostgreSQL tests cover transaction rollback and atomic audit failure behavior. Phase 4 creates no event; the frozen Phase 2 outbox regression suite remains green, and Phase 5 separately verifies its approved deferred future-consumer event.
8. HTTP application tests cover scope/validation/Problem Details, correlation, safe diagnostics, bounded history and authorization denial.
9. Architecture tests prove Capacity domain does not depend on Transfer, Referral, Matching, Spring persistence adapters, or interoperability libraries.

## Implementation order and Phase 4 exit gate

Recommended: dimension/value model → source/policy and time semantics → append and correction path → query-time evidence reconstruction → readiness evaluation → API/scope/audit → temporal/concurrency failure suite. Phase 3 Directory must be frozen first.

**PASS only if** a local contributor can submit attributed synthetic evidence, query deterministic current/readiness outcomes reconstructed from immutable evidence and policy, and observe stale/unknown/conflict honestly; real PostgreSQL transaction tests pass; auth/audit/idempotency/error/log behavior is verified; module ownership is enforced; no Reservation or later business context is scaffolded; no external public service or patient data is used. The gate does not claim Phase 7 reservation correctness.

## Explicit non-goals

No Phase 5–7 implementation, reservation, bed ledger, clinical judgement, source connector, FHIR/DHIS2, CareCapability-to-CapacityDimension mapping, Phase 4 business event, universal freshness duration, confidence scoring, or production data collection.
