# Reference Workflow: Emergency Inter-Facility Referral and Transfer

**Purpose:** Cross-phase domain workflow reference. This is not implementation code or a clinical protocol. The [Volume II architecture](../architecture/volume-2-core-care-coordination.md), [context contracts](../architecture/volume-2-context-contracts.md), and [state machines](../architecture/volume-2-state-machines.md) are normative for Phases 3–7.

Local clinical protocols, jurisdictional authority, capacity meanings, freshness periods, response/hold expiry, escalation contacts and retention must be set by each deployment. AyuVahak may be omitted when another system already owns this workflow.

## Responsibilities and invariants

- The referring care team assesses the patient and states the structured Care Requirement. AyuVahak does not infer clinical need or triage.
- Directory describes Organizations, Facilities, Locations, HealthcareServices, and direct attributed capability assertions. It does not say a destination can accept a patient now.
- Capacity records source-attributed time-bounded evidence and a deterministic readiness evaluation. Missing, stale, expired, conflicted or unavailable evidence is not positive.
- Matching returns an immutable, policy-versioned advisory result with candidate reasons and evidence. An authorized human chooses whether to pursue a destination.
- Referral, Transfer Request attempt, Acceptance, Capacity Reservation, Transfer, Transport, Arrival, and Handover are separate facts/lifecycles.
- One TransferJourney has at most one pending destination request and one nonterminal Transfer. A later destination is a new attempt after the earlier request or Transfer is terminal and any hold is reconciled; parallel offers are not the Volume II baseline.
- Receiving Clinical Suitability, Operational Readiness and Acceptance are distinct policy functions. Deployments map staff, teams or governed processes to those functions; no universal job title is assumed. Both assessments must support before explicit Acceptance.
- Acceptance is not Reservation. Capacity owns an AyuVahak coordination hold; it is not a physical bed ledger, admission or real-world guarantee. A failed or uncertain hold leaves the Transfer unreserved.
- Transport dispatch and fleet authority remain outside Volume II. `IN_TRANSIT` is an attributed reported state, not proof AyuVahak dispatched a vehicle.
- Arrival does not imply Handover. A patient may physically arrive before digital updates; record the actual fact without inventing missing prior events.
- The default Referral stores an opaque issuer-scoped PatientReference and structured coordination fields, not demographics, chart, diagnosis, summary, narrative note or attachment. Clinical detail stays in authorized clinical systems/direct channels.

## Happy path

1. The origin submits a Referral revision with origin, required CareCapability codes, typed constraints, an asserted coordination priority where configured, and an opaque source PatientReference.
2. Directory finds active Facilities and direct verified capability assertions within configured scope. It excludes no facility solely because location is missing; such a facility is unlocated and cannot enter a distance-ranked set.
3. Matching resolves each required CareCapability through its own versioned operational mapping to one or more CapacityDimensions. Capacity evaluates those explicit dimensions generically and supplies time-specific source evidence. Matching can use only current `READY` evidence for confirmed ranking; stale/unknown/conflicted candidates remain non-rankable and explained.
4. Matching creates an immutable Recommendation Snapshot with Directory/Capacity evidence references, timestamps, policy versions, rank and reasons. No clinical judgement is made.
5. An authorized origin-side human reviews candidates and explicitly selects one eligible destination to contact. Immediately before request creation, Transfer synchronously asks Matching to revalidate the exact snapshot, candidate and Referral revision. Only `APPLICABLE` permits the request; `NOT_APPLICABLE` requires a new evaluation, and `INDETERMINATE` blocks while required owner facts are unavailable. The historic snapshot is not rewritten, and Directory/Capacity changes do not require invalidation events.
6. Transfer creates one destination-specific Transfer Request attempt with an expiry. A selected receiving participant may read the opaque source-scoped PatientReference through a purpose-bound Referral view after the request exists; the request does not copy clinical data. At the receiving facility, separate locally authorized functions record Clinical Suitability and Operational Readiness Assessments. Only when both are positive may the Acceptance Authority accept.
7. Acceptance is persisted, and Transfer creates a linked Transfer in `AWAITING_RESERVATION`. Capacity is called through its owner contract to reserve a configured coordination dimension/quantity/interval.
8. If Capacity atomically confirms the hold, Transfer records `READY`. If Capacity conflicts, is unknown, or times out, Acceptance remains historical but Transfer is not ready or reserved. Same-operation retry/query reconciles lost responses.
9. An authorized actor/source reports departure and `IN_TRANSIT` when known. AyuVahak does not create a Transport assignment or instruct dispatch.
10. The authorized receiving source reports Arrival with actual occurrence time and recorded time. A separate receiving authority acknowledges Handover. Transfer completes only after explicit closure; Referral resolves through its own idempotent reaction.

## Failure and reroute path

```text
Referral R1 → Recommendation S1 → Facility B requested → rejected
           → recompute if evidence/policy changed → Facility C requested → accepted
           → Capacity reservation conflict (one-unit race lost)
           → preserve B/C attempts and C acceptance/conflict
           → close/cancel unreserved Transfer and reconcile any hold
           → recompute current evidence → Facility D requested → accepted
           → Capacity reservation confirmed → Transfer proceeds → Arrival → Handover → Complete
```

A timeout is not rejection. A late acceptance after expiry is rejected as a state transition; it does not reopen the request or create a hold. A capacity change after a Recommendation or Acceptance must be re-evaluated. A later capacity decrease after Reservation records `AT_RISK`; it does not silently release or guarantee a physical resource. If Referral is cancelled, Transfer receives a durable event and reconciles its own request/hold state. Rerouting never overwrites an earlier attempt or recommendation.

## Cross-context sequence

```mermaid
sequenceDiagram
  actor Origin as Origin-side authorized actor
  participant Referral
  participant Directory
  participant Capacity
  participant Matching
  participant Transfer
  actor Receiving as Receiving-side functional authorities

  Origin->>Referral: submit structured requirement + PatientReference
  Referral->>Directory: validate origin and capability references
  Origin->>Matching: request evaluation for exact Referral revision
  Matching->>Referral: read submitted revision
  Matching->>Directory: query candidates and direct capability evidence
  Matching->>Capacity: query current readiness evidence
  Matching-->>Origin: immutable advisory snapshot + reasons
  Origin->>Transfer: create one attempt for chosen eligible candidate
  Transfer->>Referral: validate revision and active state
  Transfer->>Matching: synchronously revalidate exact snapshot, candidate, referral revision
  Matching->>Referral: verify current submitted revision
  Matching->>Directory: verify active facility + required direct capability assertions
  Matching->>Capacity: verify freshness/readiness for mapped dimensions
  Matching-->>Transfer: APPLICABLE / NOT_APPLICABLE / INDETERMINATE + reasons
  Note over Transfer,Matching: Only APPLICABLE permits request creation; this is not a reservation
  Receiving->>Transfer: submit separate assessments
  Receiving->>Transfer: explicit Acceptance after both support
  Transfer->>Capacity: idempotent reservation command
  alt reservation confirmed
    Capacity-->>Transfer: reservation reference + expiry
    Transfer-->>Origin: READY (coordination hold confirmed)
    Origin->>Transfer: report departure, actual Arrival, Handover, completion
    Transfer-->>Referral: transfer.completed.v1
  else conflict, stale evidence, unavailable, or lost response
    Capacity-->>Transfer: conflict/unknown or response delayed
    Transfer-->>Origin: accepted but unreserved / pending reconciliation
    Origin->>Matching: recompute before next request if current evidence changed
  end
```

This sequence shows application boundaries, not network topology. Contexts remain one modular monolith; external authority adapters are optional and owned by the context needing them. No transaction spans Transfer and Capacity.

## Network interruption and delayed facts

If connectivity is lost, only committed/confirmed local facts are shown as such. An outbound response timeout means pending/unknown, not acceptance or reservation. Authorized teams may use local clinical/operational phone or radio workflows; later reconciliation records both the actual event time and the time/actor/source that entered it. A late Arrival may be recorded from an accepted-unreserved or exception state when physical arrival preceded digital updates, but the record must retain the coordination exception and cannot fabricate a prior Capacity confirmation.

Duplicate commands use owner-local idempotency. Duplicate events use consumer-owned deduplication. Corrections append a provenance-linked fact; they do not erase earlier chronology. No global ordering is inferred from clocks; aggregate sequence is used where Phase 2 requires causal ordering.

## Volume II boundaries

This workflow ends at completed coordination through Arrival and Handover. Volume II does not implement Transport, EMS, ambulance assignment/location, notification gateways, clinical handover documentation, FHIR/IPS/mCSD/DHIS2/OpenHIE connectors, frontend, analytics, offline write synchronization, or federation. The initial request contains no complete clinical summary; a future authorized clinical exchange requires a later interoperability/privacy design.
