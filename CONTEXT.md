# AyuVahak Domain Context

AyuVahak is an optional coordination system for health systems whose organizations need a shared, accountable cross-facility workflow. Its unit of work is a purpose-limited coordination case, not a patient's lifetime clinical record. If an existing system already owns the required referral and transfer workflow, a deployment may integrate with that system or omit AyuVahak.

## The coordination problem

A referring care team identifies a need for care beyond its current facility. The team states the Care Requirement and remains responsible for clinical judgement. AyuVahak helps discover possible destinations, assess attributed operational evidence, request a receiving facility's response, coordinate a capacity commitment and patient movement, and reconcile arrival and handover.

These are related but distinct decisions and facts. Receiving-side authority is defined by function and mapped by local policy, not by a universal job title:

- The Referral describes the care need and coordination request.
- A Destination Recommendation describes what the platform could justify from available evidence at a point in time.
- A Clinical Suitability Assessment and an Operational Readiness Assessment are separately attributable receiving-side facts. An Acceptance Authority records the facility's willingness only after both are positive. Local policy may map each function to a clinician, transfer-center nurse, bed manager, specialty registrar, referral desk, command center, team, or governed process; one person may perform multiple functions only when explicitly authorized.
- Reservation records a separate operational commitment.
- Transfer records the patient movement and its completion. Referral status summarizes requests and linked Transfer attempts; Transfer remains authoritative for reservation and movement details.
- Transport records an arrangement for moving the patient; dispatch authority remains with the transport organization.
- Arrival and Handover are separate facts.

A recommendation cannot promise future availability. Acceptance cannot stand in for a reservation. A reservation cannot stand in for admission. Arrival cannot stand in for clinical handover.

## Authority is category-specific

There is no universal “healthcare source of truth.” A clinical system may be authoritative for a patient's record, a facility registry for facility identity, the locally designated receiving-side authority for a current capacity observation, an identity provider for authentication, and AyuVahak for the coordination history it records when selected as workflow owner. Copies, caches, and exchanged summaries retain their source and observation time; they do not silently become authoritative.

AyuVahak owns the coordination state necessary to run and audit its workflow. It avoids owning records already authoritative elsewhere. A coordination view can be wrong or stale even when its source was authoritative when it supplied the information.

## Decision rights and trust

Clinical teams state care needs and retain clinical decision rights. A locally mapped Clinical Suitability Authority assesses whether the destination can meet the case requirement. A separately mapped Operational Readiness Authority assesses current operational ability. An Acceptance Authority records facility willingness after both assessments are positive. Each is a decision function, not a universal job title; local policy maps it to roles, teams, or an explicitly governed process. Dispatch authorities assign transport. Administrators govern local participation and access. AyuVahak may surface evidence, exclusions, freshness, and a deterministic explanation; it does not make clinical triage decisions or silently commit a destination.

Users and external systems are authenticated and authorized for a specific organization, role, case, and purpose. Information supplied by a trusted system is still checked for provenance, scope, and freshness. Least privilege applies to patient context, operational capacity, analytics, and technical operations independently.

## System boundary and evolution

AyuVahak begins when an authorized referral is created and its minimum necessary context is available in a deployment that has selected AyuVahak as the workflow owner. Its coordination responsibility ends when the case is completed through arrival and handover, or explicitly cancelled or marked unfulfilled with the outcome recorded. Downstream clinical care remains in the receiving clinical system.

The product can operate with human-maintained directories and capacity observations before mature interoperability is available. Each domain responsibility owns the meaning of the external facts it consumes; shared interoperability capabilities can assist adapters without becoming the universal gateway or owner of a business workflow. The product should preserve provenance and expose uncertainty in every environment. Over time, standardized exchanges and federation can reduce manual work, but they do not change the domain's authority rules or turn AyuVahak into an EHR, health-information exchange, dispatch platform, or public-health transaction system.


