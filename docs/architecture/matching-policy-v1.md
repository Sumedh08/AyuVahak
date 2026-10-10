# Destination Matching and Recommendation Policy v1

**Owner:** Matching bounded context  
**Applies:** Phase 6 baseline; Transfer consumes snapshots but remains the owner of destination request decisions.  
**Status:** Deterministic advisory policy; no clinical automation.

## Purpose and limits

Matching answers: “Which currently evidenced Facilities satisfy the submitted Care Requirement, and in what explainable order does the active deployment policy present them?” It does not decide what care is needed, diagnose, choose a destination for a clinician, contact a facility, accept a patient, reserve capacity, or dispatch transport. A user with the required authority explicitly chooses whether and whom to contact.

## Inputs and versioned policy

Evaluation inputs are an immutable submitted Referral revision, exact required CareCapability codes and hard constraints, origin Facility/location, active Directory results/provenance, current Capacity readiness for the CapacityDimensions resolved by that policy version, and an immutable Matching Policy version. Matching submits the deduplicated dimension set and one shared evaluation time to Capacity's bounded batch `EvaluateOperationalReadinessBatch` contract. Every requested Facility/dimension pair must have an explicit owner result. If a required context query is unavailable or incomplete, or a capability mapping is missing, return an explicit evaluation failure or indeterminate result; do not return an apparently complete ranking from partial data. The policy records:

- required capability matching semantics (v1 requires direct active assertions; no implicit parent/child inheritance);
- versioned mapping from each required CareCapability to its operational CapacityDimensions;
- geographic search scope, deployment-approved candidate-organization boundary, and missing-coordinate treatment;
- freshness/readiness gates and which Capacity policy version applies;
- whether a configured travel-time estimate is the primary distance measure or straight-line distance is used;
- any explicit deployment preference order among otherwise eligible destinations;
- tie-break rules, applicability lifetime, and policy identity/version.

Policies do not contain inferred diagnosis rules, opaque weights, machine-learned scores, or free-form executable code. A policy change creates a new immutable version. Numeric radii and maximum snapshot ages must be supplied by deployment governance before live recommendation use; there is no universal radius or freshness duration.

### CareCapability to operational requirements

Directory owns stable, directly asserted CareCapability facts. Referral records only the CareCapability codes asserted by the origin. Capacity owns dimension definitions, observations, source policy, and generic readiness evaluation. Matching alone owns the deterministic translation between them as part of its immutable Matching Policy version; this is not a new bounded context.

For v1, every CareCapability required for a confirmed actionable recommendation must map to a non-empty set of known CapacityDimensions. The resolved set is the union of mapped sets, with duplicate dimensions evaluated once. No production or clinically universal default mapping is supplied. Synthetic policy fixtures may use fictional examples such as `SYN-NEUROSURGERY` → `{SYN-NEURO-TEAM-READY, SYN-OR-READY}`. Callers cannot submit or override dimensions in recommendation requests. If any required capability lacks a configured mapping, Matching returns a non-positive `INDETERMINATE` outcome with `OPERATIONAL_MAPPING_MISSING`; it does not ask Capacity to infer a mapping or treat the missing mapping as no requirement. A deployment mapping change creates a new Matching Policy version and never rewrites prior snapshots.

## Candidate generation and eligibility

Candidate generation is bounded by explicit origin and policy scope, active Facilities, and Directory queries. It does not enumerate national records implicitly. A facility is disqualified with explicit reason if it is inactive, outside a hard geographic/organizational constraint, or lacks any mandatory direct active capability assertion. A missing/unknown facility location is not distance zero and is not silently treated as outside the radius: it receives `DESTINATION_LOCATION_UNKNOWN` and cannot enter a distance-ranked set. Directory capability assignment without sufficient source status/verification is `CAPABILITY_EVIDENCE_UNKNOWN`, not a positive match.

The Referral's `permittedGeographyCodes` are not interchangeable with Organization IDs or coordinates. Until a deployment-approved mapping connects those codes to authoritative candidate geography evidence, a non-empty list yields `GEOGRAPHIC_CONSTRAINT_UNRESOLVED`; Matching must not produce a positive recommendation by guessing. A later policy version may resolve this only when the mapping source, scope, and candidate evidence are explicit. A required destination coordinate that is absent remains candidate-level `INDETERMINATE` (`DESTINATION_LOCATION_UNKNOWN`).

Directory's exact-code assessment is preserved explicitly: `SATISFIED` is accepted only with at least one direct, active, current, verified assertion; a known absent assertion, inactive capability/offering, or no current assertion is `INELIGIBLE`; unknown capability/evidence and disputed or unverified assertions are `INDETERMINATE`. No parent capability is substituted for a required child code.

Operational eligibility first resolves every required CareCapability through the exact Matching Policy mapping version, then asks Capacity for readiness over the resulting unique CapacityDimension set. Missing mapping produces `INDETERMINATE` with `OPERATIONAL_MAPPING_MISSING`; no caller can inject dimensions or convert the missing mapping to an empty requirement. Capacity itself never receives CareCapability codes.

Operational eligibility uses Capacity's published readiness result for all mapped operational dimensions:

- `READY` with current evidence: operationally rankable;
- `NOT_READY`: ineligible with explicit failed dimensions;
- `UNKNOWN`, `STALE`, `CONFLICTED`, or integration `UNAVAILABLE`: not confirmed eligible and not rankable. Keep the candidate in an explanatory non-rankable outcome where the search scope supports showing it; never score missing data as zero or penalize it into a fabricated rank.

Evaluation completeness is separate from an individual candidate outcome. If the Directory candidate universe is incomplete, a required mapping is missing, a required geography constraint cannot be resolved, or Capacity evidence is unavailable, missing, or time-mismatched, the overall evaluation is `INCOMPLETE` and supplies no rankable candidates—even if an individually observed candidate passes its known checks. Explicit stale, unknown, or conflicted evidence can still produce a complete evaluation with that candidate `INDETERMINATE` when all required sources and candidate pages were returned.

Clinical Suitability is not inferred or evaluated by Matching. A candidate list is not a clinical decision. Destination requests require fresh, current eligibility at request time, even if a prior snapshot was valid when generated.

## Ranking

V1 uses a stable lexicographic ordering, not arbitrary numeric weights:

1. Only candidates that pass all hard capability/geography/organizational constraints and have current `READY` operational evidence enter the confirmed ordered list.
2. Use the configured travel-time estimate when the deployment policy explicitly selects that source and the result is current and valid. If unavailable, use straight-line distance in meters only where the policy allows distance fallback. A distance fallback is explicitly labeled `STRAIGHT_LINE_DISTANCE`, never ETA.
3. Apply a configured destination preference ordinal only if one is supplied by authorized deployment policy; it cannot override eligibility or readiness. If no preference is configured, this sort key is absent.
4. Use immutable AyuVahak Facility UUIDv4 byte-order/lexical representation as final tie-break for repeatable total order. This is a determinism key, not a quality claim.

When v1 uses straight-line distance, it calculates the great-circle distance between WGS84 points with the Haversine formula and mean Earth radius `6,371,008.8 m`, rounded to `0.001 m`. The evidence identifies this as `STRAIGHT_LINE_DISTANCE` with unit `m` and calculation source `STRAIGHT_LINE_HAVERSINE_V1`. This approximation is not road distance or travel time. A travel-time estimate retains its source code/reference, seconds unit, observation time, and validity end; it is usable only when observed by the evaluation time and not expired then. If any eligible candidate lacks a current travel-time estimate, the policy either applies distance fallback consistently to the whole eligible set or returns no ordered recommendation.

If the configured primary metric is unavailable and distance fallback is not allowed, no ordered recommendation is returned; candidates remain `INDETERMINATE`. The system does not invent a score or use patient-sensitive attributes.

## Structured recommendation evidence

Each immutable snapshot records:

- recommendation ID, Referral reference and exact revision;
- generatedAt, applicability deadline, Matching policy ID/version, Capability Operational Requirement Policy mapping version, and Directory/Capacity policy/projection versions;
- the resolved CareCapability-to-CapacityDimension mapping used for each required capability, including shared dimensions evaluated once;
- candidate reference and evaluation outcome (`RANKED_ELIGIBLE`, `INELIGIBLE`, `INDETERMINATE`), stable rank only for eligible candidates;
- each mandatory capability result and source-attributed evidence reference/version;
- each required Capacity dimension outcome, value kind/unit (not patient data), source, `observedAt`, `receivedAt`, `validUntil`, freshness, provenance reference and readiness reason;
- geographic scope result, origin/destination location references and versions, metric type/value/unit/time/source, and whether it is straight-line distance or travel-time estimate;
- deterministic structured reason codes for inclusion, exclusion, uncertainty, and rank order;
- actor/service, organization context, correlation, purpose, and evaluation outcome attribution under Phase 2 contracts.

Store references and the minimal fact snapshot necessary to reproduce why the result was produced. Do not store a Referral narrative, patient identity, name, clinical summary, or full Directory/Capacity history in a recommendation. The snapshot is immutable. The UI/API may render human-readable explanations from structured reason codes, but free text alone is not sufficient evidence.

## Historical validity and current applicability

Historical validity means the snapshot remains an immutable, accurate explanation of evidence and policy at `generatedAt`. Current applicability is separate and is never represented by a stored `current=true` flag or maintained through assumed change-event invalidation.

`applicableUntil` is no later than the earliest applicable Capacity evidence validity end and the required deployment-configured recommendation-age limit. `RevalidateRecommendationApplicability(snapshot, candidate, referralRevision)` performs targeted synchronous checks through owner contracts: the exact Referral revision remains current and submitted, not cancelled/resolved; the candidate Facility remains active; each required direct capability assertion remains active, effective, and verified; each mapped CapacityDimension still has sufficiently current evidence and usable readiness; the exact Matching policy/mapping version remains permitted for new use; and the configured maximum snapshot age has not elapsed. It returns `APPLICABLE`, `NOT_APPLICABLE`, or `INDETERMINATE` with structured reason codes. An unavailable or ambiguous dependency returns `INDETERMINATE`, never applicable.

`GetCurrentRecommendation` means the latest historical snapshot plus a freshly computed applicability result. Revalidation reads only material facts referenced by the snapshot, not whole context databases. Directory/Capacity change events are not required for correctness. Transfer must block a new request unless revalidation returns `APPLICABLE`; otherwise it requires a fresh recommendation. Recalculation creates a new snapshot linked to the prior one and never updates historical evidence in place.

## API/authorization and concurrency

Evaluation is an explicit command for a submitted Referral revision under the originating organization/facility context and Matching-owned `RecommendationRequest` authority; no background autonomous decision is implied. Referral, Directory, and Capacity owner reads execute through a separate deployment-configured Matching service identity with owner-held, target-scoped read grants. It never inherits human rights. Read views are scoped and bounded. Concurrent evaluations of the same revision/policy/evidence may converge on an equivalent snapshot, but their generatedAt and evidence versions remain attributable; idempotent replay must not create a second business recommendation for the same operation key/fingerprint.

Referral cancellation/revision racing evaluation is handled by re-reading the exact submitted revision after collecting owner evidence and immediately before snapshot creation. If that read is already stale, the command rejects without writing a snapshot. Referral and Matching do not share a transaction, so this is not an atomic compare-and-swap and cannot prevent a later concurrent change. A snapshot remains historical evidence for the exact source revision and is never itself proof of currentness; current applicability requires synchronous owner revalidation. Revalidation and request creation are separate context transactions, not a hidden distributed transaction or reservation; Capacity and receiving authorities re-check their own facts at later decisions.

## Verification scenarios for Phase 6

Use synthetic facilities and deterministic policy/evidence fixtures. Verify: nearest Facility lacks required capability; only farther Facility passes all hard requirements; direct capability assertion vs parent-only; every required CareCapability has a non-empty Mapping and all resolved dimensions are readiness checked; shared dimensions are deduplicated; caller cannot inject mappings; mapping version is retained in the snapshot; unknown/stale/conflicted/unavailable capacity remains non-rankable; explicitly observed unavailable quantity yields ineligible; unlocated candidate behavior; configured travel-time vs distance fallback labels; fallback disabled; configured preference cannot override eligibility; identical inputs/policy give identical ordering and reasons; stable tie-break; policy version change creates a new result; capacity/directory/referral changes are detected by synchronous current-applicability checks without invalidating or rewriting history; recommendation versus concurrent Referral amendment/cancellation; historical snapshot remains reconstructable after changes; and no patient data is present in snapshot/event/log output.

## Non-goals

No clinical triage, diagnosis, treatment, patient-outcome prediction, ML/LLM, weighted opaque score, cross-country policy, external route adapter, FHIR/mCSD/IPS mapping, automatic facility contact, Acceptance, Reservation, Transport, or UI design is included.
