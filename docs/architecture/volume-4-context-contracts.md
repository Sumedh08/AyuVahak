# Volume IV Context Contracts

These are design-time contracts for future Phase 11/12 implementation. They do not create runtime endpoints or alter frozen Volumes I–III. Every contract uses owner application boundaries; private repositories and tables are never shared.

## Contract principles

- A caller supplies a named operation and opaque owner reference, never an arbitrary destination URL.
- The authoritative owner validates business semantics, scope, current version/freshness, audit and idempotency.
- External and peer inputs remain untrusted even when authenticated or schema-valid.
- Cross-boundary delivery is at-least-once. Stable identity and deduplication replace exactly-once claims.
- PatientReference and clinical payload are excluded except for the already approved Referral-owned patient-context disclosure path; that path is not used by federation or Analytics.

## Phase 11 contracts

| Contract | Caller → owner | Request/response | Authority, freshness and errors | Idempotency, privacy and audit |
|---|---|---|---|---|
| Notification publication | Eligible owner event → platform notification projection | Owner resource type/opaque ID/version, safe event type, validated scope; result is a hint sequence | Owner commit is authoritative; projection may lag or be rebuilt; clients refresh on gap | Dedup by internal event ID; no PatientReference/clinical body; bounded audit/telemetry without high-cardinality labels |
| Client event stream | Authenticated client → notification delivery | Authorized scope and last scoped cursor; emits resource/version hints or `refresh-required` | Scope revalidated at subscribe/reconnect; resource API reauthorizes; expired cursor requires full authorized refresh | Cursor bounded and opaque; no credentials in URL; revoked grant closes/ages stream |
| Remote Directory discovery | Matching/Directory owner adapter → configured peer Directory contract | Namespaced query and approved fields; returns source-scoped facts with version/provenance/freshness | Peer Directory owns facts; missing, stale, revoked or unavailable response is explicit and cannot be upgraded to local authority | Partner/profile-scoped operation key; bounded projection; no patient data; audit peer and scope |
| Remote Capacity evidence | Matching/Transfer owner adapter → configured peer Capacity contract | Facility ref, named evidence request; returns evidence, source, observed/received time, validUntil, policy/version | Receiving Capacity owns readiness/reservation; stale/unavailable maps to UNKNOWN/STALE and blocks actions requiring ready evidence | Stable query/operation reference; no patient data; audit and correlation |
| Federated receiving request | Origin Transfer → receiving Transfer boundary | Stable operation ID, origin deployment/ref, selected destination and minimum approved coordination facts; response returns distinct clinical assessment, operational assessment, Acceptance, and attributed provenance/status | Receiving Transfer owns each receiving decision; origin owns journey and records sourced outcome; `OUTCOME_UNKNOWN` is not rejection; patient context is separate Referral disclosure after participant validation | Peer + operation ID + fingerprint; changed fingerprint conflicts; no Referral PatientReference or clinical payload; audit both sides |
| Remote reservation and status | Origin Transfer → receiving Transfer boundary, then receiving Transfer → its local Capacity owner contract | Stable remote request ID and distinct stable Capacity operation ID; response includes local reservation reference/outcome or pending/unknown | Acceptance precedes Capacity attempt; a failure remains accepted-but-unreserved; Capacity commits its own hold; no cross-context/cross-deployment transaction; status query repeats same ID | Same operation identity recovers owner outcome; safe dedup; no duplicate hold; audit and causal IDs |
| Federated movement fact | Transport/Transfer owner → named remote Transfer contract | Minimal attributed departure/arrival/interruption fact, source sequence/version and timestamps | Receiving Transfer validates its own state and records its own attributed fact; stale/out-of-order messages do not overwrite newer owner truth | Peer/message ID/fingerprint; at-least-once dedup; no clinical data; durable security audit |

Federation contracts are synchronous request/response over Phase 9 where a caller needs a decision, with durable local pending/unknown operation state around network uncertainty. Notifications or internal events may wake processing, but do not replace operation status reconciliation.

## Phase 12 reporting contracts

| Contract | Caller → owner | Request/response | Authority, freshness and errors | Idempotency, privacy and audit |
|---|---|---|---|---|
| Owner reporting snapshot | Analytics → one business owner | Approved measure keys, organization scope, explicit `[start,end)` period and definition version; returns bounded measure values/status, immutable source snapshot reference, owner cutoff/version, completeness and generated time | Owner computes from its own data and rejects unsupported measures/scope; timeout/missing cursor is incomplete, not zero | Request key includes owner, measure, period, scope and source cutoff; no person/case IDs or free text; source snapshot remains owner-owned; audit reporting purpose |
| Aggregate computation | Owner snapshots → Analytics | Versioned bounded measure set and dimensions, immutable source snapshot references, owner cutoff/version, completeness and generated time | Analytics owns derived result; mismatched version, partial source or missing required input yields explicit non-complete status | Computation keyed by indicator version/period/org/dimensions/source cutoffs; exact bounded measure inputs are retained for reproducibility; revision is immutable; audit corrections |
| DHIS2 export | Analytics → Phase 9 configured partner → DHIS2 | One immutable aggregate revision mapped to bounded DataValue identities and approved profile | Analytics owns the aggregate; DHIS2 import summary is delivery evidence only; partial, invalid and unknown outcomes remain distinct | Stable local export operation per revision/target tuple; no patient data or unnecessary payload persistence; audit target, mapping and outcome |
| DHIS2 reconciliation/read-back | Analytics operator/process → configured Phase 9 read operation | Target tuple and operation identity | Only configured authorized read-back may resolve unknown; absent support means `OUTCOME_UNKNOWN`/operator action | Same operation identity; never blind overwrite; audit who resolved and evidence used |
| Federated aggregate combination | Analytics → authorized peer Analytics | Indicator/version, period, geography, source deployment, revision, completeness and aggregate value | Combine only compatible approved definitions and complete inputs; incompatible or duplicate attribution blocks aggregation | Dedup source deployment + indicator/version + period + dimensions + revision; aggregate only, no raw histories or patient identifiers |

## Authorization and failure rules

All peer operations require a currently active Phase 9 trust profile plus owner-level operation and scope authorization. OAuth/mTLS authentication does not grant an AyuVahak permission. Revocation is checked before a queued attempt and before inbound owner dispatch. Audit unavailability fails closed for protected peer operation or disclosure as defined by the existing owner policy.

Timeout, process restart, or lost response after a possible remote commit yields `OUTCOME_UNKNOWN` where the external contract cannot prove the result. A transport-layer retry is distinct from federated message replay, owner command idempotency, internal event deduplication and DHIS2 replacement semantics.

## Prohibited contract shapes

- Shared SQL/repository access between deployments or contexts.
- “Replicate all changes” or generic entity synchronization.
- Arbitrary peer URL or FHIR/DHIS2 proxy supplied by a business caller.
- Full aggregate or clinical payload in notification/federation events.
- Analytics query access to private context schemas.
- DHIS2 Tracker/Event export or PatientReference export.
- Exactly-once delivery claims.
