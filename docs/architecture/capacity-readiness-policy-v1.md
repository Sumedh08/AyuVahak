# Capacity and Operational Readiness Policy v1

**Owner:** Capacity bounded context  
**Applies:** Phase 4 baseline; Phase 6 may consume only the published Capacity contract.  
**Policy posture:** Explicit evidence and uncertainty; no universal capacity truth or freshness duration is assumed.

## Observation model

A Capacity Observation is an immutable, source-attributed assertion about one Facility and one controlled resource/capacity dimension. It contains:

- a stable AyuVahak observation identifier and optional source-scoped external observation identifier;
- Facility and dimension references, quantity/unit **or** a controlled categorical state (never both for one observation);
- source organization/system or authorized reporting actor, reporting method, and provenance;
- `observedAt`, server-assigned `receivedAt`, and a source-supplied or policy-derived `validUntil` when meaningful;
- correction/supersession reference where applicable;
- optional source-declared quality category only when the source provides a defined meaning. AyuVahak does not invent a numeric confidence score.

Capacity dimensions are a deployment-governed bounded catalog, not arbitrary JSON. A dimension identifies unit, allowed value kind/range, whether it contributes to a readiness rule, and whether it can be reserved. Examples include a defined count of coordination-available critical-care beds or a controlled service state. A “bed” count must define whether the number means physical total, currently staffed, or available to coordination. Only a dimension explicitly defined as `available-to-coordination` and reservable can support a Capacity Reservation. No stock or workforce ledger is created.

Duplicate receipt with the same source-scoped observation ID and same normalized fact returns the prior result. Reuse of the same source ID with a different fact is a conflict and is retained for investigation, not silently overwritten. Manual observations use the same evidence structure and need an authenticated actor, facility scope, method, time, and reason; manual does not automatically mean lower trust.

## Time and validity

- `observedAt` says when the reporting source asserts the fact applied.
- `receivedAt` records when AyuVahak accepted it and is server-controlled.
- `validUntil` is exclusive. An observation is expired at or after that instant.
- A per-dimension policy may derive an expiry from `observedAt` when the source provides no expiry; no freshness duration is globally assumed.
- A future-skewed observation beyond a validated deployment tolerance is quarantined from current state; its original timestamp and provenance remain available to restricted review. Do not clamp it to make it appear fresh.
- Freshness is evaluated at query/decision time, not only by a scheduled projection refresh. A missed background expiry task cannot make expired evidence current.

Freshness states are:

| State | Meaning | Decision use |
| --- | --- | --- |
| `CURRENT` | The selected evidence is valid now under the active dimension policy. | May support a readiness result, subject to source and value semantics. |
| `STALE` | Evidence exists but exceeds the configured decision age or otherwise fails current-freshness policy. | Cannot support positive availability/readiness or new ranking. |
| `EXPIRED` | Evidence's explicit or derived validity end has passed. | Excluded from current projection; retained in history. |
| `UNKNOWN` | No valid usable evidence or policy is available for the question. | Never interpreted as zero/available/ready. |
| `CONFLICTED` | Equally authoritative current evidence disagrees, or source/policy ambiguity prevents a safe selection. | Cannot support positive readiness/reservation. |
| `SUPERSEDED` | A later attributed correction/retraction explicitly replaces this fact. | Historical only; not current. |

## Source policy and current projection

For each capacity dimension, an authorized deployment policy assigns source priority and allowed fallback behavior. The policy is versioned and read-only to ordinary reporters. There is no global rule such as HIS always outranks manual data.

Projection algorithm:

1. Exclude invalid, future-skewed, superseded, expired, retracted, out-of-scope, or malformed observations.
2. Keep only observations valid at the requested decision instant under the dimension's freshness policy.
3. Select the highest-priority source tier with usable evidence. A lower-priority source is eligible only when this dimension's policy explicitly permits fallback for absent/stale higher-tier evidence; the result identifies that fallback.
4. Within the selected source tier, choose the latest `observedAt`, not latest `receivedAt`.
5. If equal-priority observations at the selected observation time disagree, return `CONFLICTED`; if they agree, retain all provenance and select a deterministic representative for the projection.
6. If no allowed tier has usable evidence, return `UNKNOWN`, `STALE`, or `EXPIRED` with reasons. Do not produce numeric zero unless zero was explicitly observed as a valid typed quantity.

The initial Capacity implementation derives current evidence at query time from immutable observations/corrections and the selected owner policy inside a repeatable-read Capacity transaction. Observation ingestion commits the immutable fact with required audit and idempotency state; it does not update a mutable projection. This keeps history as the source of truth and avoids a second state that could become stale. Query-time reconstruction is deterministic, and freshness is re-evaluated at the requested decision instant. No projection-update lock or distributed lock is required. A correction appends a correcting/retracting fact and supersedes the original; it never destroys the original observation. A materialized projection may be considered later only if measured query cost requires it; it must remain rebuildable and preserve the same source, freshness, provenance, and conflict semantics.

## Readiness evaluation

`OperationalReadiness` is a deterministic evidence evaluation over an explicit `CapacityDimension` set, a versioned readiness rule set, and an evaluation time. Its conceptual contract is `EvaluateOperationalReadiness(facility, requiredDimensions, policyContext, evaluationTime)`. Capacity validates and evaluates only the supplied dimensions; it does not consume Referral, infer CareCapabilities, or decide that (for example) neurosurgery requires particular dimensions. The versioned Capability Operational Requirement Policy belongs to Matching, which translates Referral CareCapabilities into dimensions before calling Capacity. Referral continues to store CareCapability codes only.

An unknown or unconfigured required CapacityDimension, missing readiness rule, or unavailable required evidence produces `UNKNOWN`/`UNAVAILABLE`, never `READY`. The internal caller cannot supply an ad hoc CareCapability-to-dimension mapping; it supplies only the resolved dimension set authorized by the owning application contract. This is distinct from the case-specific Operational Readiness Assessment that a receiving authority records in Phase 7. Capacity does not perform clinical judgement.

Phase 4 exposes the single-Facility `EvaluateOperationalReadiness` contract. Phase 6 adds a bounded owner operation, `EvaluateOperationalReadinessBatch`, for one Matching evaluation: it accepts a bounded set of explicitly authorized Facility scopes, one deduplicated dimension set, and one evaluation instant; it returns an explicit outcome for every requested Facility/dimension pair from one read-only repeatable-read transaction. Capacity validates the Matching service grant independently at each Facility and records service-actor read audit per target. Any invalid or unauthorized pair rejects the batch without a partial positive result. The batch does not change the readiness semantics above and does not permit Matching to read Capacity persistence directly.

Result states and precedence:

1. `NOT_READY` if any required dimension has an explicit current value that fails the rule (for example, unavailable service or insufficient explicitly reported quantity).
2. Otherwise `CONFLICTED` if any required dimension has unresolved equal-authority conflict.
3. Otherwise `STALE` if required evidence exists but is stale/expired.
4. Otherwise `UNKNOWN` if evidence, source authority, required dimension definition, or readiness policy is missing.
5. `READY` only if every required dimension has current usable evidence and every configured rule passes.

The result includes the exact required CapacityDimensions, outcome per dimension, value kind/unit, selected source, observed/received/validity times, source-policy and readiness-policy versions, and reason codes. Unknown dimensions are named. A `READY` system result does not equal the receiving authority's acceptance or clinical suitability.

## Reservations (introduced in Phase 7)

Capacity is the only owner of a coordination Reservation and its capacity holds. Reservation is not part of Phase 4 acceptance. For a dimension configured as reservable, Capacity compares current eligible coordination quantity minus all active holds for the same Facility, dimension, unit, and overlapping time interval. The check and new hold commit atomically inside Capacity. If evidence is not current or enough unheld quantity is not proven, result is `CONFLICTED`/`UNKNOWN`; it does not reserve.

A later source change may make an active hold `AT_RISK`. That flag records that the world/evidence no longer supports the prior hold; it does not auto-release, guarantee a bed, or rewrite the earlier reservation. Capacity emits `capacity.reservation-at-risk.v1` for Transfer to reconcile. Release/expiry is explicit and audited. The hold and projection never claim that the facility physically has a bed available.

## API, security, audit, and recovery constraints

- Report commands require validated organization/facility scope and a Capacity reporting authority. Queries are scoped to authorized operational context and purpose.
- External/manual retries use an owner-local idempotency key and stable source observation identity. Same key/fingerprint replays the safe outcome; changed content under the same key conflicts.
- Manual corrections/overrides, source-policy changes, explicit retractions, reservations, releases, and risk resolutions require mutation audit with source provenance. Sensitive operational evidence queries are durably audited only where the deployment's access policy declares them sensitive.
- An audit failure rolls back the corresponding protected Capacity mutation per ADR 0012. If a later phase defines an event for an observation or reservation, its owning transaction must commit the outbox intent atomically; a failed publication cannot undo the committed state, and the intent remains recoverable per ADR 0007. Phase 4 defines no Capacity event/outbox intent, and Capacity Reservation begins only in Phase 7.
- Normal logs include operation, normalized route, outcome, source/system reference only when safe, timing, and correlation. They do not include patient context or raw source payload.

## Verification scenarios for Phase 4 / Phase 7

Use a local PostgreSQL/PostGIS container and synthetic evidence. Phase 4 verifies source priority and permitted fallback, duplicate observation idempotency, same source ID with changed fact, equal-priority tie disagreement, out-of-order `observedAt`, future clock skew, freshness/expiry boundary, no-observation unknown, explicit zero, supersession, deterministic query-time reconstruction, concurrent observation, and rollback on audit failure. Re-run the Phase 2 outbox recovery regression suite as part of full verification; Phase 4 defines no Capacity event or outbox intent. Phase 7 additionally verifies reservation last-unit races, overlap/time/unit scoping, simultaneous observation update, duplicate reservation command, unknown/stale evidence denial, expiry, release, and later `AT_RISK` notification. No public system or real health data is a test target.

## Configuration decisions and safe defaults

The system may not enable a capacity dimension for readiness or reservation until its owner has defined allowed value/unit, trusted-source policy, fallback, freshness/validity policy, and human/manual reporting responsibility. Undefined configuration yields `UNKNOWN` and blocks positive decisions. Numeric freshness periods, observation polling, legal retention, capacity stock semantics, and throughput targets are deployment/evidence decisions, not architecture constants.
