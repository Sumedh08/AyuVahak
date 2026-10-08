# Phase 6 Specification — Destination Matching and Recommendation

**Volume:** II — Core Care Coordination  
**Phase boundary:** Deterministic advisory matching using Phases 3–5 contracts; no Transfer Request or Reservation.  
**Architecture references:** [Volume II architecture](../architecture/volume-2-core-care-coordination.md), [matching policy v1](../architecture/matching-policy-v1.md), [context contracts](../architecture/volume-2-context-contracts.md), [state machines](../architecture/volume-2-state-machines.md), [ADR 0006](../adr/0006-deterministic-advisory-matching.md), [ADR 0016](../adr/0016-deterministic-recommendation-snapshots.md), [ADR 0018](../adr/0018-matching-owns-capability-operational-mapping.md), [ADR 0019](../adr/0019-synchronous-recommendation-applicability.md).

## Objective and outcome

For one submitted Referral revision, produce an immutable and attributable explanation of which Facilities satisfy explicit hard requirements, which are excluded/indeterminate and why, and a repeatable ordering of currently eligible candidates. It is advisory only. It does not choose or contact the destination.

## Scope and exclusions

**Included:** explicit candidate search bounds; Matching-owned versioned capability-to-operational-dimension policy; hard eligibility; Directory and Capacity application contracts; optional travel-time port; versioned deterministic lexicographic ranking; structured evidence; immutable recommendation snapshot/history plus synchronous applicability revalidation; scoped command/query API; audit/provenance/idempotency; local synthetic policy/golden tests.

**Excluded:** clinical reasoning/triage; ML/LLM; hidden numeric weights; automatic destination selection; cross-facility reservation; acceptance/transfer; external route implementation; raw clinical context; external FHIR/mCSD/DHIS2 data model; transport dispatch.

## Model and algorithm

Matching owns `MatchingPolicyVersion`, `CandidateEvaluation`, `RecommendationSnapshot`, and the minimal source evidence necessary to explain each evaluation. It references Referral/Directory/Capacity IDs and versions but never owns their concepts or data.

Evaluation inputs are one exact submitted Referral revision and policy version, active Directory candidate/assertion evidence, current Capacity readiness by required dimension, and optional configured travel-time evidence. Matching alone owns the versioned mapping from each required CareCapability to one or more `CapacityDimension` values. Referral stores required CareCapability codes only; Directory stores direct capability assertions only; Capacity evaluates an explicit dimension set generically and never translates a CareCapability. Matching resolves and deduplicates the union of mapped dimensions before querying Capacity. A required capability with no non-empty mapping makes the evaluation `INDETERMINATE` with `OPERATIONAL_MAPPING_MISSING`; callers cannot provide or override the mapping. Mapping changes create a new policy version and never rewrite an existing snapshot. If a required context query is unavailable or incomplete, return an explicit evaluation failure or indeterminate result; do not return an apparently complete ranking from partial data.

For each candidate:

1. Apply hard organization/geographic constraints from the requirement and policy.
2. Require every mandatory CareCapability to be directly assigned, active and sufficiently verified. Parent code does not imply child capability.
3. Resolve the required dimensions from the selected Matching policy, union/deduplicate them, and require current `READY` from Capacity for each. `NOT_READY` is `INELIGIBLE`; `UNKNOWN`, `STALE`, `CONFLICTED`, and unavailable are `INDETERMINATE` and excluded from confirmed rank.
4. Require a usable destination location for a distance-ranked search. Missing location is `INDETERMINATE`, not zero or silently outside radius.
5. Order only eligible candidates using configured travel-time estimate or explicitly permitted straight-line-distance fallback, then optional deployment preference, then stable Facility UUIDv4 tie-break. Policy source/metric/fallback is recorded.

`RecommendationSnapshot` includes referral/revision; candidate reference/outcome and reason codes; Directory capability and Capacity evidence references/version, selected values/units/source/times/freshness; geographic metric/source/value/time; ranking, eligibility, and capability-to-dimension mapping policy versions; the resolved dimension mapping used; generatedAt and applicableUntil; ranks; actor/service and validated scope/correlation. It does not include PatientReference, clinical narrative, demographics, raw partner payload, or entire context snapshots. The full snapshot schema is an implementation concern; the information boundary is mandatory. Snapshot evidence is immutable and remains a truthful record of its original evaluation even when later inapplicable.

## Contracts, commands, and queries

### Command

`GenerateRecommendation(referral, revision, policy)` requires submitted current Referral revision, validated originating organization/facility context, `RecommendationRequest` authority, and audit when deployment classifies the evaluation as safety-relevant. Idempotency is RECOMMENDED: an exact retry replays/returns equivalent result; a changed referral/policy/evidence fingerprint creates a new evaluation only with explicit new command/idempotency key.

### Queries

- `GetRecommendation(snapshotId)` returns its immutable structured evidence to an authorized participant.
- `GetCurrentRecommendation(referralId)` identifies the latest historical snapshot and returns a freshly determined applicability result; it does not overwrite historical results or trust a stored `current=true` flag.
- `RevalidateRecommendationApplicability(snapshotId, candidateFacility, exactReferralRevision)` performs targeted synchronous reads from the owning contexts and returns `APPLICABLE`, `NOT_APPLICABLE`, or `INDETERMINATE` with reason codes.
- `ListRecommendations(referralId)` is bounded and scoped.

### HTTP resource contract

| Method and route | Purpose |
| --- | --- |
| `POST /api/v1/recommendations` | Explicitly evaluate one submitted Referral revision under a selected active policy; requires `Idempotency-Key` for retried evaluation. |
| `GET /api/v1/recommendations/{recommendationId}` | Read immutable snapshot and structured reasons for an authorized participant. |
| `GET /api/v1/referrals/{referralId}/recommendations` | Bounded history/latest pointer plus current applicability status. |

The create request identifies Referral and submitted revision; policy selection is server-authorized or deployment-configured, not an untrusted caller-supplied policy body. A result status may be `COMPLETE`, `INDETERMINATE`, or `NO_ELIGIBLE_DESTINATION` with per-candidate outcomes; missing integration evidence never yields partial-looking success.

Matching calls Referral for one revision, Directory for candidates/capability/location evidence, and Capacity for time-specific readiness using the mapped `CapacityDimension` set. It does not join their tables or call external adapters directly. Travel-time port receives facility location references only, never a patient identifier or case narrative.

## Historical snapshot and current applicability

Historical validity means “this is what the inputs/policy supported at `generatedAt`.” Current applicability means “this snapshot may be used to initiate a new Transfer Request now.” It is computed at use time by `RevalidateRecommendationApplicability`; it is not a mutable boolean invalidated by change events. The check confirms: (1) the exact Referral revision is still current, submitted, and neither cancelled nor resolved; (2) the selected Facility remains active; (3) the direct required capability assertions remain active, effective, and sufficiently verified; (4) the mapped Capacity evidence/readiness remains current and ready for every resolved dimension; (5) the snapshot's policy and mapping versions remain permitted for new use; and (6) the recommendation and evidence remain within configured age limits. A changed/withdrawn/expired fact returns `NOT_APPLICABLE`; an unavailable, incomplete, or ambiguous owner response returns `INDETERMINATE`. Either result blocks request creation and requires a fresh Recommendation. Matching queries only the snapshot's relevant owner facts, never whole databases. No Directory or Capacity invalidation event is required. The check and later Transfer command are separate context transactions, so it is not a reservation or guarantee; Acceptance and Reservation recheck their own facts. Reevaluation creates a new linked snapshot; old rank/reasons are never rewritten.

No universal maximum age is set. The deployment must supply one before live use; absent configuration blocks generating an actionable current recommendation.

## Authorization, audit, errors and privacy

Recommendation request/view uses Phase 2 explicit organization/facility context and generic matching functional authority; local roles map by deployment. No anonymous case endpoint. Audit evaluation when policy marks it safety-relevant and always retain enough attribution in snapshot metadata. Do not audit/record patient payload; include no patient identifier in metrics/logs/events.

Errors use RFC 9457 and stable codes: `MATCHING_REFERRAL_NOT_SUBMITTED`, `MATCHING_REFERRAL_REVISION_STALE`, `MATCHING_POLICY_UNCONFIGURED`, `MATCHING_NO_CANDIDATES`, `MATCHING_EVIDENCE_UNAVAILABLE`, `MATCHING_SNAPSHOT_NOT_FOUND`, `MATCHING_SNAPSHOT_NOT_APPLICABLE`, `MATCHING_SCOPE_MISMATCH`, and safe `INTERNAL_ERROR`. “No eligible destination” is a valid result with per-candidate reasons, not a server error.

## Persistence and concurrency

Matching owns only policy/version reference and recommendation snapshots. It keeps no copy of full Referral, Directory or Capacity data. A snapshot write and any required audit/outbox intent are one Matching-owned transaction. Query all relevant evidence before creating a snapshot; record exact context version references. If the Referral changes while evaluation runs, compare revision at commit; persist an historical evaluation only if it is explicitly marked non-current and cannot be used for transfer, or reject/retry the operation. The implementation must choose one deterministic option; the initial rule is to reject the command with `MATCHING_REFERRAL_REVISION_STALE` and require reevaluation.

No events are emitted in Phase 6 because Transfer consumes a current snapshot and applicability result by synchronous query and Referral does not need each result asynchronously. Matching does not write Capacity or Directory. Matching's own idempotency receipt is separate from event consumer dedup.

## TDD seams and golden scenarios

- Closest candidate lacks one mandatory capability; farther candidate is only eligible and ranks first.
- All required capability assertions exist but one is unverified, inactive or parent-only; candidate is not confirmed eligible.
- Required CareCapability has a valid mapping to one or more dimensions; all mapped dimensions are evaluated and duplicate dimensions are queried once.
- A missing mapping or caller-supplied mapping attempt produces a non-positive indeterminate result; no caller can override server-owned policy.
- Mapping-policy change creates a distinct snapshot with the new mapping version; prior snapshots retain their recorded mapping and explanation.
- Multiple CareCapabilities sharing a dimension use the deduplicated union; one CareCapability may map to multiple dimensions and every dimension is required.
- Capacity stale, unknown, conflicted, not ready, or unavailable for any mapped dimension; no false positive rank.
- Explicit quantity zero vs missing observation are distinguishable.
- Two equal candidates order identically using stable tie-break; repeated evaluation with identical versions/policy is deterministic.
- Travel time, distance fallback, missing route, and disabled fallback are labeled distinctly; no distance-as-ETA wording.
- Preference order cannot override a hard gate; changing policy version creates a distinct snapshot.
- Historical snapshots remain readable after a Referral revision/cancellation, Facility deactivation, capability assertion change, Capacity evidence/policy change, Matching policy/mapping retirement, or age deadline; the synchronous revalidation returns the corresponding `NOT_APPLICABLE` or `INDETERMINATE` outcome without rewriting the snapshot.
- Revalidation succeeds only when the exact Referral revision is current/submitted, Facility active, required direct capability assertions valid, all mapped dimensions freshly ready, policy versions permitted, and age limits unexpired.
- An unavailable Directory, Capacity, Referral, or policy owner prevents applicability from becoming positive.
- The synchronous applicability query targets only the candidate and evidence referenced by the snapshot and emits no invalidation event.
- Race between matching and Referral revision/cancellation fails safely.
- No recommendation snapshot, event, metric, trace, or normal log contains PatientReference or clinical fields.
- Test HTTP/API errors and scope through real application seam and PostgreSQL; prove no direct cross-context table access with architecture tests.

## Implementation order and Phase 6 exit gate

Recommended: define immutable policy inputs → candidate and evidence contracts → eligibility result → lexicographic rank → evidence snapshot and applicability → authorized application/API → golden scenario/consistency suite. Freeze Phases 3, 4 and 5 first.

**PASS only if** every result can be reproduced/explained from policy and evidence versions; ineligible/indeterminate candidates are visibly distinguished; no unauthorized/partial data becomes a positive recommendation; ordering is deterministic; revisions invalidate future use but preserve history; API/privacy/authorization/audit and architecture rules pass on local synthetic infrastructure; no Transfer or Reservation is required to demonstrate recommendation.

## Explicit non-goals

No clinical judgment, ML, AI, weighted score, autonomous routing/selection, request acceptance, reservation, transfer, external route service integration, FHIR model, patient summary storage or analytics.
