# AyuVahak Glossary

This glossary defines the product's canonical domain language. Use these terms consistently across product, architecture, and workflow documentation.

## Care coordination

**Referral**  
A coordination request from a referring facility for care at another facility. It carries the care need and the minimum patient context needed to seek a destination; it is not the patient's longitudinal record.

**Care Requirement**  
The requested care capabilities and constraints a destination must satisfy, as stated by the responsible clinical team. It is not a diagnosis or a recommendation from AyuVahak.

**CareCapability**

A governed code describing a stable care-relevant ability that a Facility directly asserts it can provide. A code hierarchy does not imply that a Facility has a child capability unless the assertion is explicit.

**Destination Candidate**  
A facility considered for a referral. A candidate may be eligible, ineligible, or awaiting evidence; candidate status alone does not mean the facility was contacted or selected.

**Destination Recommendation**  
An explainable, time-specific advisory ranking of destination candidates using stated requirements and attributed directory, capability, capacity, and travel observations. It is not a clinical decision, a booking, or a guarantee that the destination remains available.

**Recommendation Snapshot**

An immutable record of one matching evaluation: the Referral revision, policy and capability-to-operational-requirement mapping versions, candidate outcomes, evidence references and times, reasons, and ordering at the time. Its historical explanation remains valid as a record even after it is no longer applicable to a new action.

**Capability Operational Requirement Policy**  
A versioned Matching policy that maps each CareCapability required by a Referral to one or more CapacityDimensions needed to evaluate operational readiness. It does not redefine capability or capacity, and is not stored in Referral, Directory, or Capacity. A missing mapping cannot support a positive recommendation.

**Transfer Request**  
A directed request to a receiving facility to accept a referral under stated care, timing, and operational conditions. It may be accepted, rejected, allowed to expire, or withdrawn.

**Transfer Request Attempt**

One destination-specific Transfer Request in a Referral's ordered coordination journey. A later destination is a new attempt; rejection, expiry, withdrawal, and prior evidence remain in history.

**Transfer Journey**

The Transfer context's ordered coordination history for one Referral, including destination request attempts and the current or completed Transfer. It does not own the Referral or Capacity records it references.

**Acceptance**  
An explicit facility-level response that the facility is willing to proceed under stated conditions. It may be recorded only after separate positive Clinical Suitability and Operational Readiness Assessments from authorities designated by local policy. The assessments are distinct facts even if one person is locally authorized to make both. Acceptance does not reserve capacity, constitute admission, or guarantee that circumstances will remain unchanged.

**Clinical Suitability Assessment**  
A receiving-side judgement about whether the facility can meet the stated Care Requirement for this case. It is made or confirmed by a Clinical Suitability Authority under local policy; AyuVahak does not make the clinical judgement.

**Clinical Suitability Authority**  
A person, team, or explicitly governed process authorized by a deployment to make or attest to a Clinical Suitability Assessment. The term names a decision function, not a universal job title.

**Reservation**  
A time-bounded operational commitment by an authorized facility or system to hold stated receiving capacity for a transfer. A reservation is not a physical bed assignment or admission unless the authoritative local system explicitly defines it that way.

**Capacity Reservation**

An AyuVahak Capacity-owned coordination hold for a defined resource dimension, quantity/unit, time interval, and Transfer reference. It protects only the coordination quantity represented in AyuVahak; it does not guarantee a physical resource.

**Transfer**  
The coordinated movement of a patient from a referring facility to a receiving facility after a destination is agreed. Transfer planning and lifecycle are distinct from both the referral request and the transport arrangement.

**Transport**  
The vehicle, crew, mode, and journey arrangement used to move a patient. A transfer can exist before transport is assigned, and AyuVahak does not thereby become the transport dispatch authority.

**Transport Request**

A request to coordinate movement for a Transfer. It is a separate transport lifecycle, does not itself assign a vehicle, and contains only the operational facts needed to coordinate movement.

**Transport Assignment**

A provider- or dispatcher-reported association of a transport resource with a Transport Request. It is not proof that a patient boarded, departed, or reached a destination.

**Transport Status Observation**

An attributed report about the transport request or movement, including its source and observed/received time. It is evidence to reconcile, not automatically a Transfer lifecycle transition.

**ETA Observation**

A source-attributed estimate of expected arrival time, with its observation time and freshness. It is not a guarantee or proof of movement.

**Movement Evidence**

An attributed report that a patient boarded, departed, or arrived. Transport retains its source observation; Transfer separately decides whether to record its own movement fact.

**Destination Change**

A Transfer-owned change to the currently selected receiving facility. It requires the applicable destination checks and authority; after movement begins, it also requires an acknowledged transport instruction. A provider acknowledgement is evidence for Transfer to validate, not a direct change to Transfer state.

**Transport Destination-Change Operation**

A Transport-owned request to a provider or authorized manual transport participant to change an active movement's destination instruction. Its accepted, rejected, or unknown outcome is separate from Transfer's decision to apply a new destination.

**External Exchange Operation**

A purpose-limited attempt by an AyuVahak owner context to exchange a named message or request with a configured partner. External delivery status is distinct from the owner context's business outcome.

**Partner Trust Profile**

Deployment-controlled facts that identify an external partner and bound which configured operations, directions, endpoints, protocols, and authentication methods may be used. It does not establish that partner data is correct or grant a human AyuVahak permission.

**Dispatch**  
The act of assigning or activating a transport resource by the authorized transport-dispatch organization. Recording a dispatch status does not mean AyuVahak dispatched the resource.

**Arrival**  
The event of the patient reaching the receiving facility. It is distinct from a vehicle reaching a location and may be recorded after it physically occurred.

**Handover**  
The clinical communication from the transferring care team to the receiving care team, including acknowledgement by a locally authorized receiving care-team member. Arrival does not imply that handover is complete.

## Health organizations and service capability

**Organization**  
A legally or administratively recognized entity that governs, funds, or delivers healthcare or coordination. An organization may operate multiple facilities.

**Facility**  
A healthcare site or operational unit where care is delivered. A facility can offer multiple healthcare services and contain multiple locations.

**Location**  
A physical place or area associated with an organization or facility, such as a campus, building, department, or coordinate. A location is not itself a service or a statement of capacity.

**Healthcare Service**  
An organized care offering delivered by an organization at one or more facilities, such as emergency care or imaging. The service describes what is offered, not whether it can accept another patient now.

**Capability**  
A care-relevant ability a facility can provide, such as a specialty, procedure, equipment, or level of care. Capability is a relatively stable assertion and does not establish current staffing, readiness, or availability.

**Capacity**  
The amount or scope of workload or resource a service can handle over a defined time interval. Capacity needs a unit and time context; a nominal capacity is not current availability.

**Availability**  
An attributed observation of whether and how much capacity can currently be used for a stated purpose and time interval. Availability can change and must be interpreted with its observation time and source.

**Readiness**  
The facility's current operational ability to accept a specific care requirement, considering relevant capabilities, staffing, equipment, service status, and constraints. Readiness is a contextual judgement, not a permanent facility trait or a universal score.

## Operational evidence and information authority

**Operational Readiness Assessment**  
A receiving-side judgement about whether the facility can operationally accept this case now, considering relevant capacity, staffing, equipment, service status, and constraints. It is separate from clinical suitability.

**Operational Readiness Authority**  
A person, team, or explicitly governed process authorized by a deployment to make or attest to an Operational Readiness Assessment. The term names a decision function, not a universal job title.

**Acceptance Authority**  
A person, team, or explicitly governed process authorized by a deployment to record the facility's Acceptance after the required separate assessments are positive. Local policy identifies the authority and whether the same person may perform more than one receiving-side function.

**Capacity Observation**  
A time-stamped assertion from a named source about capacity, availability, or readiness. It is evidence available to coordination, not a guarantee of the real-world state.

**Current Capacity Evidence**

The Capacity context's policy-derived view of which observations are usable for a particular dimension and decision time, including freshness, source precedence, and conflict outcome. It is not the source system's live database state.

**Operational Readiness Evaluation**

A deterministic evaluation of current Capacity evidence against stated operational dimensions. It can be READY, NOT_READY, UNKNOWN, STALE, or CONFLICTED and is distinct from a receiving authority's case-specific Operational Readiness Assessment.

**Observed Time**

The time at which a source asserts that an observation applied. It is distinct from the time AyuVahak received or recorded it.

**Received Time**

The server-recorded time AyuVahak accepted an observation or message. It does not make an older observation current.

**Applicability**

Whether a historically recorded Recommendation Snapshot may still be used for a new action. It is determined synchronously at use time from the snapshot's age and material current Referral, Directory, Capacity, and Matching policy facts; it is not a stored boolean invalidated by assumed change events. If any required check is unavailable, applicability is INDETERMINATE. Historical validity is distinct: the snapshot remains a valid record of what its evidence and policy supported when generated.

**Freshness**  
The relationship between an observation's time and its policy-defined period of validity for a particular decision. Fresh data may still be inaccurate; stale data must not be presented as current.

**Provenance**  
Information describing where a datum came from, who or what supplied it, when it was observed, and any material transformation or verification.

**Source System**  
The system or accountable organization that creates or maintains a particular category of information. A system is authoritative only for the categories it is responsible for.

**External Identifier**  
An identifier issued by an external system or authority. Its meaning is scoped to that issuer and resource type; it is not interchangeable with an AyuVahak identifier or with another issuer's identifier.

**Patient Context**  
The minimum case-specific patient identity and clinical/logistical facts needed for a coordination action. It is purpose-limited and is not a copy of the patient's complete record. The Volume II default is an opaque source-scoped PatientReference; additional fields require an explicit purpose and privacy decision.

**Patient Summary**  
A concise clinical summary supplied by an authoritative clinical source for a particular care purpose. An IPS may be one exchange representation of a patient summary; neither is the complete longitudinal record.

**Coordination Event**  
A recorded occurrence relevant to the Referral, Transfer Request, assessment, Acceptance, Reservation, Transfer, Transport, Arrival, or Handover lifecycle, attributed to its actor or source and time.

**AyuVahak Actor**

A human or machine identity that performs or reports an action within AyuVahak. It is distinct from the patient and is interpreted with its issuer/source and authorized organizational scope.

**Authentication Identity**

An identity established by an external identity provider for a human or service. It proves which subject presented a valid credential; it does not by itself grant access to an AyuVahak organization, case, or action.

**Service Identity**

A separately governed machine principal used by an integration or background process. It has its own scope and attribution and does not inherit a human's authority.

**Organization Execution Context**

The validated organization and optional facility under which an action is attempted, associated with the authenticated actor and request purpose. A caller's unverified selection is not an execution context.

**Functional Authority**

A decision or action responsibility such as Clinical Suitability, Operational Readiness, or Acceptance. A deployment maps its local roles, teams, or governed processes to these functions; no universal job title is implied.

**Authorization Grant**

An attributable, scoped permission assigned under a deployment's policy. Authentication establishes a subject; a grant determines whether that subject may perform a particular action in context.

**Audit Record**

A restricted record of who or what acted, within which organization/context, on what target, when, with what outcome and correlation. It supports accountability and does not replace a clinical record, operational log, domain fact, or data provenance.

**Domain Event**

A notification that an owning context committed a fact for another AyuVahak context to consume. The producer owns the fact's meaning; delivery does not itself constitute a receiving party's business decision or a publication to an external health system.

**Deployment Identity**

A stable identifier for one independently operated AyuVahak deployment. It is distinct from the identity of an organization, facility, user, or external partner and scopes remote resource references and peer trust.

**Federation Peer**

An independently administered deployment with an explicit, versioned, revocable trust relationship that permits named operations, contexts, directions, and scopes. Authentication identifies the peer; owner policy separately authorizes each business operation.

**Remote Reference**

A reference namespaced by deployment, context, and resource identifier. A matching UUID in another deployment does not establish that the resources are the same or share authority.

**Notification Hint**

A bounded message that tells an authorized client an owner resource may have changed. It is not the resource state; the client retrieves current data through an authorized owner query.

**Reporting Snapshot**

A bounded, owner-produced set of approved aggregate measures for an explicit scope and period, accompanied by source cutoff/version, completeness, provenance, and generation time. It contains no patient or case identifier.

**Aggregate Snapshot**

An immutable Analytics result for one indicator version, reporting period, organizational scope, allowed dimensions, source cutoff, completeness status, and revision. A correction creates a linked later revision.

**Reporting Completeness**

Evidence that all required owner inputs for a defined indicator, scope, and period are available and fresh. Missing, partial, or stale inputs are distinct from a complete numeric zero.

**Suppression**

A governed release-set decision to withhold or coarsen one or more related aggregate cells because the approved privacy policy finds them too revealing alone or by differencing with totals, dimensions, periods, revisions, prior outputs, or known external releases. Suppression is not a zero value and is retained as an explicit quality outcome.

## Ambiguous words resolved

- “Referral” means the request to coordinate care; “Transfer” means the resulting patient movement.
- “Acceptance” means willingness to proceed; “Reservation” means a separate, explicit capacity commitment.
- “Clinical Suitability Assessment,” “Operational Readiness Assessment,” and “Acceptance” are separate receiving-side facts. Local policy maps authority for each function; a job title is not universal.
- “Capability” means what a facility can provide in principle; “Availability” means an observation of what may be used now; “Readiness” assesses whether the facility can accept this particular requirement now.
- “Recommendation” is advisory; a human with appropriate authority chooses whether to contact or proceed with a destination.
- Use “Transfer Request” for the directed request and its response lifecycle; do not use “offer” as a separate domain concept.
- “Current” is never inferred from a source label alone; it is evaluated using provenance and freshness policy.
- A Capacity Observation is evidence; a Current Capacity Evidence projection is an interpretation under a versioned policy. Neither equals a receiving authority's Operational Readiness Assessment.
- A Recommendation Snapshot explains a past evaluation; current Applicability must be checked again before a Transfer Request.
- A Transfer Request Attempt belongs to a Transfer Journey; it is not a Referral revision or a Transfer movement.
- A Capacity Reservation is owned by the Capacity context and is a coordination hold, not an external bed ledger.
- “Bed,” “slot,” and “available” must not be used as interchangeable measures. Any capacity quantity must name its resource/unit, time interval, source, and meaning.



