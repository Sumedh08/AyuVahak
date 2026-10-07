# AyuVahak Glossary

This glossary defines the product's canonical domain language. Use these terms consistently across product, architecture, and workflow documentation.

## Care coordination

**Referral**  
A coordination request from a referring facility for care at another facility. It carries the care need and the minimum patient context needed to seek a destination; it is not the patient's longitudinal record.

**Care Requirement**  
The requested care capabilities and constraints a destination must satisfy, as stated by the responsible clinical team. It is not a diagnosis or a recommendation from AyuVahak.

**Destination Candidate**  
A facility considered for a referral. A candidate may be eligible, ineligible, or awaiting evidence; candidate status alone does not mean the facility was contacted or selected.

**Destination Recommendation**  
An explainable, time-specific advisory ranking of destination candidates using stated requirements and attributed directory, capability, capacity, and travel observations. It is not a clinical decision, a booking, or a guarantee that the destination remains available.

**Transfer Request**  
A directed request to a receiving facility to accept a referral under stated care, timing, and operational conditions. It may be accepted, rejected, allowed to expire, or withdrawn.

**Acceptance**  
An explicit facility-level response that the facility is willing to proceed under stated conditions. It may be recorded only after separate positive Clinical Suitability and Operational Readiness Assessments from authorities designated by local policy. The assessments are distinct facts even if one person is locally authorized to make both. Acceptance does not reserve capacity, constitute admission, or guarantee that circumstances will remain unchanged.

**Clinical Suitability Assessment**  
A receiving-side judgement about whether the facility can meet the stated Care Requirement for this case. It is made or confirmed by a Clinical Suitability Authority under local policy; AyuVahak does not make the clinical judgement.

**Clinical Suitability Authority**  
A person, team, or explicitly governed process authorized by a deployment to make or attest to a Clinical Suitability Assessment. The term names a decision function, not a universal job title.

**Reservation**  
A time-bounded operational commitment by an authorized facility or system to hold stated receiving capacity for a transfer. A reservation is not a physical bed assignment or admission unless the authoritative local system explicitly defines it that way.

**Transfer**  
The coordinated movement of a patient from a referring facility to a receiving facility after a destination is agreed. Transfer planning and lifecycle are distinct from both the referral request and the transport arrangement.

**Transport**  
The vehicle, crew, mode, and journey arrangement used to move a patient. A transfer can exist before transport is assigned, and AyuVahak does not thereby become the transport dispatch authority.

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

**Freshness**  
The relationship between an observation's time and its policy-defined period of validity for a particular decision. Fresh data may still be inaccurate; stale data must not be presented as current.

**Provenance**  
Information describing where a datum came from, who or what supplied it, when it was observed, and any material transformation or verification.

**Source System**  
The system or accountable organization that creates or maintains a particular category of information. A system is authoritative only for the categories it is responsible for.

**External Identifier**  
An identifier issued by an external system or authority. Its meaning is scoped to that issuer and resource type; it is not interchangeable with an AyuVahak identifier or with another issuer's identifier.

**Patient Context**  
The minimum case-specific patient identity and clinical/logistical facts needed for a coordination action. It is purpose-limited and is not a copy of the patient's complete record.

**Patient Summary**  
A concise clinical summary supplied by an authoritative clinical source for a particular care purpose. An IPS may be one exchange representation of a patient summary; neither is the complete longitudinal record.

**Coordination Event**  
A recorded occurrence relevant to the Referral, Transfer Request, assessment, Acceptance, Reservation, Transfer, Transport, Arrival, or Handover lifecycle, attributed to its actor or source and time.

## Ambiguous words resolved

- “Referral” means the request to coordinate care; “Transfer” means the resulting patient movement.
- “Acceptance” means willingness to proceed; “Reservation” means a separate, explicit capacity commitment.
- “Clinical Suitability Assessment,” “Operational Readiness Assessment,” and “Acceptance” are separate receiving-side facts. Local policy maps authority for each function; a job title is not universal.
- “Capability” means what a facility can provide in principle; “Availability” means an observation of what may be used now; “Readiness” assesses whether the facility can accept this particular requirement now.
- “Recommendation” is advisory; a human with appropriate authority chooses whether to contact or proceed with a destination.
- Use “Transfer Request” for the directed request and its response lifecycle; do not use “offer” as a separate domain concept.
- “Current” is never inferred from a source label alone; it is evaluated using provenance and freshness policy.
- “Bed,” “slot,” and “available” must not be used as interchangeable measures. Any capacity quantity must name its resource/unit, time interval, source, and meaning.



