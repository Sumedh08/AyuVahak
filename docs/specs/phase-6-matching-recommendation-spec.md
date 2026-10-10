# Phase 6 Specification — Destination Matching and Recommendation

**Volume:** II — Core Care Coordination  
**Phase boundary:** Deterministic advisory matching using Phases 3–5 contracts; no Transfer Request or Reservation.  
**Architecture references:** [Volume II architecture](../architecture/volume-2-core-care-coordination.md), [matching policy v1](../architecture/matching-policy-v1.md), [context contracts](../architecture/volume-2-context-contracts.md), [state machines](../architecture/volume-2-state-machines.md), [ADR 0006](../adr/0006-deterministic-advisory-matching.md), [ADR 0016](../adr/0016-deterministic-recommendation-snapshots.md), [ADR 0018](../adr/0018-matching-owns-capability-operational-mapping.md), [ADR 0019](../adr/0019-synchronous-recommendation-applicability.md), [ADR 0022](../adr/0022-matching-owner-reads-use-a-dedicated-service-identity.md).

## Objective and outcome

For one submitted Referral revision, produce an immutable and attributable explanation of which Facilities satisfy explicit hard requirements, which are excluded/indeterminate and why, and a repeatable ordering of currently eligible candidates. It is advisory only. It does not choose or contact the destination.

## Scope and exclusions

**Included:** explicit candidate search bounds; Matching-owned versioned capability-to-operational-dimension policy; hard eligibility; Directory and Capacity application contracts; a bounded Capacity readiness batch at one evaluation time; dedicated owner-authorized Matching service identity for cross-context reads; optional travel-time port with explicit unavailable behavior; versioned deterministic lexicographic ranking; structured evidence; immutable recommendation snapshot/history plus synchronous applicability revalidation; scoped command/query API; audit/provenance/idempotency; local synthetic policy/golden tests.

**Excluded:** clinical reasoning/triage; ML/LLM; hidden numeric weights; automatic destination selection; cross-facility reservation; acceptance/transfer; external route implementation; raw clinical context; external FHIR/mCSD/DHIS2 data model; transport dispatch.

## Model and algorithm

Matching owns `MatchingPolicyVersion`, `CandidateEvaluation`, `RecommendationSnapshot`, and the minimal source evidence necessary to explain each evaluation. It references Referral/Directory/Capacity IDs and versions but never owns their concepts or data.

Evaluation inputs are one exact submitted Referral revision and policy version, a complete set of bounded Directory candidate/assertion evidence, current Capacity readiness by required dimension, and optional configured travel-time evidence. Directory returns every Facility in each explicitly queried candidate Organization, including inactive Facilities and those without matching assertions; its owner evidence makes those states explicit. Directory obtains all offset pages for one candidate Organization in a single Directory-owned repeatable-read transaction. Matching never merges pages from separate Directory snapshots as a complete candidate universe. The synchronous Referral, Directory, and Capacity owner reads use bounded JDBC statements and bounded owner transactions; a timeout is an unavailable result and must never be converted into positive evidence. If Directory cannot complete its bounded result, any page reports `hasMore`, or any owner result is unavailable, incomplete, or out of scope, the candidate universe is incomplete and must not produce a positive/complete recommendation. Matching alone owns the versioned mapping from each required CareCapability to one or more `CapacityDimension` values. Referral stores required CareCapability codes only; Directory stores direct capability assertions only; Capacity evaluates an explicit dimension set generically and never translates a CareCapability. Matching resolves and deduplicates the union of mapped dimensions before querying Capacity. A required capability with no non-empty mapping makes the evaluation `INDETERMINATE` with `OPERATIONAL_MAPPING_MISSING`; callers cannot provide or override the mapping. Mapping changes create a new policy version and never rewrite an existing snapshot. If a required context query is unavailable or incomplete, return an explicit evaluation failure or indeterminate result; do not return an apparently complete ranking from partial data.

For each candidate:

1. Apply hard organization/geographic constraints from the requirement and policy.
2. Require the candidate Facility itself to be active and every mandatory CareCapability to be directly assigned, active and sufficiently verified. Inactive Facilities and Facilities with absent, inactive, disputed, unverified or otherwise insufficient assertions remain explicit non-eligible or indeterminate candidates with reasons; they are not silently omitted. Known absence/inactivity is `INELIGIBLE`; disputed/unverified/unknown evidence is `INDETERMINATE`. Parent code does not imply child capability.
3. Resolve the required dimensions from the selected Matching policy, union/deduplicate them, and require current `READY` from Capacity for each. `NOT_READY` is `INELIGIBLE`; `UNKNOWN`, `STALE`, `CONFLICTED`, and unavailable are `INDETERMINATE` and excluded from confirmed rank.
4. Require a usable destination location for a distance-ranked search. Missing location is `INDETERMINATE`, not zero or silently outside radius.
5. Order only eligible candidates using configured travel-time estimate or explicitly permitted straight-line-distance fallback, then optional deployment preference, then stable Facility UUIDv4 tie-break. Policy source/metric/fallback is recorded.

The v1 straight-line metric is Haversine great-circle distance over WGS84 coordinates, using mean Earth radius `6,371,008.8 m` and rounding to `0.001 m`. It is labeled `STRAIGHT_LINE_DISTANCE` in meters and never described as ETA. A travel-time estimate retains its source and validity evidence and is used only when current at evaluation time. A configured provider signals temporary unavailability by throwing the typed `TravelTimePort.UnavailableException`; Matching treats that as missing route estimates and applies the same whole-set fallback rule. If an eligible destination lacks a current estimate, fallback is applied to the entire eligible set only when policy allows it; otherwise the set remains non-rankable. Unexpected adapter/programming failures remain visible as command failures and must not be converted into a fabricated distance or positive estimate.

Evaluation completeness is distinct from candidate outcome. An incomplete Directory universe, required mapping gap, unresolved required geography code, unavailable/missing Capacity result, or evidence timestamp mismatch makes the overall evaluation `INDETERMINATE` and provides no rankable candidates, even if some observed candidates individually pass. A non-empty `permittedGeographyCodes` list cannot be treated as Organization IDs or inferred from coordinates; until an explicit policy mapping to authoritative geography evidence exists, it blocks positive evaluation with `GEOGRAPHIC_CONSTRAINT_UNRESOLVED`. When required, a missing destination coordinate produces candidate-level `INDETERMINATE` with `DESTINATION_LOCATION_UNKNOWN`.

`RecommendationSnapshot` includes referral/revision; candidate reference/outcome and reason codes, preserving a subject code when a reason concerns a particular CareCapability or CapacityDimension; Directory capability assessments with outcome and available version/status even when no assertion exists; Directory assertion and Capacity evidence references/version, selected values/units/source/times/freshness; for Capacity, both source `observedAt` and server `receivedAt` when an observation exists; geographic metric/source/value/time; ranking, eligibility, and capability-to-dimension mapping policy versions; the resolved dimension mapping used; generatedAt and applicableUntil; ranks; actor/service and validated scope/correlation. It does not include PatientReference, clinical narrative, demographics, raw partner payload, or entire context snapshots. The full snapshot schema is an implementation concern; the information boundary is mandatory. Snapshot evidence is immutable and remains a truthful record of its original evaluation even when later inapplicable.

## Contracts, commands, and queries

### Command

`GenerateRecommendation(referral, revision, policy)` requires a submitted current Referral revision, validated originating organization/facility context, `RecommendationRequest` authority, an `Idempotency-Key` on every create request, and audit when deployment classifies the evaluation as safety-relevant. A retry with the same scope, key, and request fingerprint replays the recorded result; reusing that key with changed request content conflicts. A new evaluation, including one against changed policy or evidence, requires an explicit new command and a new idempotency key. Queries do not require idempotency keys.

### Queries

- `GetRecommendation(snapshotId)` returns its immutable structured evidence to an authorized participant.
- `GetCurrentRecommendation(referralId)` identifies the latest historical snapshot and returns a freshly determined applicability result; it does not overwrite historical results or trust a stored `current=true` flag.
- `RevalidateRecommendationApplicability(snapshotId, candidateFacility, exactReferralRevision)` performs targeted synchronous reads from the owning contexts and returns `APPLICABLE`, `NOT_APPLICABLE`, or `INDETERMINATE` with reason codes.
- `ListRecommendations(referralId)` is bounded and scoped.

### HTTP resource contract

| Method and route | Purpose |
| --- | --- |
| `POST /api/v1/recommendations` | Explicitly evaluate one submitted Referral revision under a selected active policy; requires `Idempotency-Key` on every create request. |
| `GET /api/v1/recommendations/{recommendationId}` | Read immutable snapshot and structured reasons for an authorized participant. |
| `GET /api/v1/referrals/{referralId}/recommendations` | Bounded history/latest pointer plus current applicability status. |

The create request identifies Referral and submitted revision; policy selection is server-authorized or deployment-configured, not an untrusted caller-supplied policy body. A result status may be `COMPLETE`, `INDETERMINATE`, or `NO_ELIGIBLE_DESTINATION` with per-candidate outcomes; missing integration evidence never yields partial-looking success.

The bounded history route accepts `limit` from 1 through 100 and an optional `candidateFacilityId`. If supplied, applicability is evaluated for that candidate against the latest snapshot; a candidate absent from that snapshot is `NOT_APPLICABLE`. If omitted, Matching evaluates the top-ranked candidate in the latest snapshot and omits applicability when no candidate was ranked. History items are ordered by generation time descending with recommendation identifier as a stable tie-break. Historical items are returned unchanged even when current applicability is `NOT_APPLICABLE` or `INDETERMINATE`.

The authenticated human requester must hold the Matching-owned `RecommendationRequest` authority at the validated Referral origin scope. Matching performs owner reads through a separately authenticated, deployment-configured machine identity; it never impersonates the requester or carries the requester's grants into another context. That identity has only the owner read grants needed for the configured deployment: `ReferralRead` at origin scopes, `DirectoryRead` within the deployment-approved candidate search boundary, and `CapacityEvidenceReview` at each explicitly authorized candidate Facility. Candidate search bounds remain explicit in the active Matching policy. If candidate scope is represented by several exact Directory organization grants, Matching queries each granted organization through the owner contract and merges bounded results deterministically; it never converts them into implicit platform access. A Directory platform-scope grant is acceptable only when the Directory instance itself is limited to that deployment boundary. A missing, revoked, or out-of-scope grant fails closed; Matching must not return a partial positive result or broaden scope automatically.

Each owner records its own read audit as the machine actor, with the validated target organization/facility, action, purpose `MATCHING_EVALUATION`, outcome, and shared correlation identifier. Matching's command audit and immutable snapshot retain the initiating human actor and origin scope. The two records are correlatable without treating the machine as the human or storing patient payload. The Referral contract returns only the submitted requirement fields needed for matching, never a `PatientReference`, narrative, demographics, or full Referral object. Directory and Capacity return bounded evidence projections. Capacity's batch contract accepts a bounded Facility set and returns a complete outcome for every requested Facility/dimension pair at one evaluation time. Matching does not join their tables or call external adapters directly. The optional travel-time port receives Facility location references only, never a patient identifier or case narrative.

## Historical snapshot and current applicability

Historical validity means “this is what the inputs/policy supported at `generatedAt`.” Current applicability means “this snapshot may be used to initiate a new Transfer Request now.” It is computed at use time by `RevalidateRecommendationApplicability`; it is not a mutable boolean invalidated by change events. The check confirms: (1) the exact Referral revision is still current, submitted, and neither cancelled nor resolved; (2) the selected Facility remains active; (3) the direct required capability assertions remain active, effective, and sufficiently verified; (4) the mapped Capacity evidence/readiness remains current and ready for every resolved dimension; (5) the snapshot's policy and mapping versions remain permitted for new use; and (6) the recommendation and evidence remain within configured age limits. A changed/withdrawn/expired fact returns `NOT_APPLICABLE`; an unavailable, incomplete, or ambiguous owner response returns `INDETERMINATE`. Either result blocks request creation and requires a fresh Recommendation. Matching queries only the snapshot's relevant owner facts, never whole databases. No Directory or Capacity invalidation event is required. The check and later Transfer command are separate context transactions, so it is not a reservation or guarantee; Acceptance and Reservation recheck their own facts. Reevaluation creates a new linked snapshot; old rank/reasons are never rewritten.

No universal maximum age is set. The deployment must supply one before live use; absent configuration blocks generating an actionable current recommendation.

## Authorization, audit, errors and privacy

Human recommendation request/view uses Phase 2 explicit organization/facility context and the Matching-owned `RecommendationRequest` authority at the Referral origin; local roles map by deployment. Owner reads use the separate machine identity described in ADR 0022, with exact provider grants and no human-token forwarding. No anonymous case endpoint. Audit evaluation when policy marks it safety-relevant and always retain initiating-actor and service attribution in snapshot/audit metadata. Do not audit/record patient payload; include no patient identifier in metrics/logs/events.

Errors use RFC 9457 and stable codes: `MATCHING_REFERRAL_NOT_SUBMITTED`, `MATCHING_REFERRAL_REVISION_STALE`, `MATCHING_POLICY_UNCONFIGURED`, `MATCHING_NO_CANDIDATES`, `MATCHING_EVIDENCE_UNAVAILABLE`, `MATCHING_SNAPSHOT_NOT_FOUND`, `MATCHING_SNAPSHOT_NOT_APPLICABLE`, `MATCHING_SCOPE_MISMATCH`, and safe `INTERNAL_ERROR`. “No eligible destination” is a valid result with per-candidate reasons, not a server error.

## Persistence and concurrency

Matching owns only policy/version reference and recommendation snapshots. It keeps no copy of full Referral, Directory or Capacity data. Query all relevant evidence before creating a snapshot; record exact context version references. Referral, Directory, Capacity, and optional travel-time reads complete before Matching opens its write transaction. A short Matching-owned transaction then checks/claims the command receipt, stores the snapshot, appends required mutation audit, and completes the receipt atomically. A completed retry is checked before owner reads, so it can replay without depending on current external evidence availability. Concurrent duplicate requests may repeat read-only evidence collection, but only one Matching snapshot/audit/receipt outcome commits.

Directory gathers all candidate pages under its own bounded repeatable-read transaction. Targeted candidate and origin-location reads also use bounded Directory-owned repeatable-read transactions. For the multi-page search, Directory records minimized successful-read audit evidence during the snapshot, commits and releases the snapshot transaction, and only then durably appends those audit records; targeted reads likewise append successful-read audit after their owner transaction closes. An unavailable audit store still fails the owner read closed; no evidence is disclosed without its required audit. This ordering avoids holding the Matching write connection or Directory snapshot connection while waiting for an independent audit connection.

Because Referral and Matching own separate transactions, a Referral read cannot lock its revision through a Matching commit. The command therefore re-reads the exact submitted Referral revision after collecting evidence and immediately before creating the snapshot. If that owner read already reports a different revision or non-submitted state, reject with `MATCHING_REFERRAL_REVISION_STALE` and write no snapshot. This is an optimistic point-in-time guard, not an atomic cross-context compare-and-swap: a Referral change after that read may race with the Matching commit. Any saved snapshot remains immutable evidence for the exact Referral version and is never a claim that the Referral is still current. Reads that present current applicability must synchronously revalidate with Referral; Phase 7 must block request creation unless the fresh applicability result is `APPLICABLE`. No consumer may infer currentness from a successful snapshot write or its age.

No events are emitted in Phase 6 because Transfer consumes a current snapshot and applicability result by synchronous query and Referral does not need each result asynchronously. Matching does not write Capacity or Directory. Matching's own idempotency receipt is separate from event consumer dedup.

## TDD seams and golden scenarios

- Closest candidate lacks one mandatory capability; farther candidate is only eligible and ranks first.
- All required capability assertions exist but one is unverified, inactive or parent-only; candidate is not confirmed eligible.
- Required CareCapability has a valid mapping to one or more dimensions; all mapped dimensions are evaluated and duplicate dimensions are queried once.
- An incomplete Directory page set, missing Capacity facility/dimension result, mismatched evaluation time, or unresolved Referral geography code cannot produce rankable candidates.
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
- A Referral change detected by the final pre-write owner read rejects evaluation without a snapshot; a change racing after that point may leave only an immutable historical snapshot that revalidation marks non-applicable and Phase 7 cannot use.
- Missing/revoked service grants at any owner fail closed, are attributable to the machine actor, and cannot yield partial positive recommendations; the initiating human command remains attributable separately.
- Capacity batch evaluation returns exactly one explicit result per requested facility/dimension at a shared evaluation time; missing or unauthorized pairs cannot appear ready.
- No recommendation snapshot, event, metric, trace, or normal log contains PatientReference or clinical fields.
- Test HTTP/API errors and scope through real application seam and PostgreSQL; prove no direct cross-context table access with architecture tests.

## Implementation order and Phase 6 exit gate

Recommended: define immutable policy inputs → candidate and evidence contracts → eligibility result → lexicographic rank → evidence snapshot and applicability → authorized application/API → golden scenario/consistency suite. Freeze Phases 3, 4 and 5 first.

**PASS only if** every result can be reproduced/explained from policy and evidence versions; ineligible/indeterminate candidates are visibly distinguished; no unauthorized/partial data becomes a positive recommendation; ordering is deterministic; revisions invalidate future use but preserve history; API/privacy/authorization/audit and architecture rules pass on local synthetic infrastructure; no Transfer or Reservation is required to demonstrate recommendation.

## Explicit non-goals

No clinical judgment, ML, AI, weighted score, autonomous routing/selection, request acceptance, reservation, transfer, external route service integration, FHIR model, patient summary storage or analytics.
