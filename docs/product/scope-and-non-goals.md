# Scope and Non-Goals

## In scope for the product direction

AyuVahak's product boundary covers the coordination work around a care request that crosses facility or organizational boundaries:

- Maintain or consume a provenance-aware directory of organizations, facilities, locations, healthcare services, and operational capabilities.
- Receive time-stamped capacity observations from authoritative local systems or accountable facility users.
- Represent a Referral and its Care Requirement, authorized patient reference, and minimum necessary Patient Context.
- Discover Destination Candidates, apply deterministic eligibility rules, and return an explainable Destination Recommendation.
- Send and track Transfer Requests, Acceptance/Reject/Timeout, and a distinct Reservation outcome.
- Coordinate a Transfer lifecycle with cancellation, rerouting, actual Arrival, and acknowledged Handover.
- Record or link a Transport request and status while leaving Dispatch authority with the transport organization; direct system-to-dispatch requests require explicit deployment authorization.
- Notify the authorized actors involved in the active case.
- Audit access and coordination actions, retain category-specific provenance, and expose limited operational summaries.
- Exchange information with EHR/HIS, registries, identity providers, HIE/interoperability layers, DHIS2, LMIS, and routing systems through appropriate standards and explicit purpose-limited contracts.

For the baseline, the receiving side records separate Clinical Suitability and Operational Readiness Assessments. The Acceptance Authority records facility Acceptance only when both are positive; Reservation is still a distinct commitment. A deployment maps these decision functions to its locally authorized people, teams, or governed processes. Job titles such as clinician, transfer nurse, bed manager, specialty registrar, referral-desk staff, or command-center staff are examples, not universal assignments.

## Explicit non-goals

AyuVahak is not:

- An EHR/EMR, HIS, clinical chart, longitudinal patient record, or central repository for clinical documents.
- A master patient index, patient identity matching service, provider credential registry, or authoritative facility registry.
- A clinical decision-support system, diagnostic tool, triage authority, treatment planner, or substitute for clinician judgement.
- An automated destination selector that commits care without human review.
- A guarantee of bed availability, clinical admission, service readiness, ambulance availability, or clinical outcome.
- A hospital bed-management, staffing, theatre scheduling, or facility resource-planning system.
- An ambulance fleet-management or emergency dispatch system. It may coordinate a request and display a status reported by the authorized transport source.
- An inventory, medicine, commodity, procurement, or supply-chain management system.
- A generic HIE, OpenHIM, interoperability engine, terminology server, or national digital-health infrastructure replacement.
- A DHIS2 substitute or the authoritative source for public-health indicators.
- A generic enterprise BI platform or a patient-level public-health surveillance system.
- A full event-sourced platform, distributed CQRS deployment, Kafka-dependent system, or Kubernetes-only deployment.
- An AI/ML-based safety-critical routing or capacity decision system.

## Concrete differentiation from adjacent systems

| Adjacent system | Its primary authority or job | AyuVahak's relationship and boundary |
| --- | --- | --- |
| EHR/EMR | Patient chart, encounters, clinical documentation, and often local referral records | Reads or receives only the context needed for an active coordination case; returns selected status if authorized. Does not replicate the chart. |
| HIS | Hospital administration and local operational workflows, often including departments, beds, and staffing | Consumes only the service/capacity facts needed across organizations. Does not replace local hospital operations or create an independent bed-management authority. |
| OpenHIE | Architecture and governance patterns for interoperable health information exchange | AyuVahak can fit within an OpenHIE-aligned ecosystem as a coordination application. It is not the exchange architecture or shared infrastructure. |
| OpenHIM | Interoperability mediation, routing, and management of health information transactions; mediators can also implement orchestration | May carry or mediate exchanges. The deployment explicitly assigns one owner for each durable workflow; AyuVahak is not mandatory when OpenHIM or another application already owns it. |
| DHIS2 | Public-health information management and aggregate indicators/program data | Receives appropriately minimized, preferably non-identifiable coordination aggregates. It is not the live transaction store for referrals or capacity. |
| OpenLMIS / LMIS | Health commodity supply-chain and stock management | Remains authoritative for medicines and commodities. AyuVahak does not copy inventory to infer care capacity. |
| Facility registry | Official organization, facility, location, or service identity | Consumed as a source where available; AyuVahak may maintain a clearly attributed local coordination projection, not replace the registry. |
| Provider registry | Practitioner identity, registration, or qualifications | Used to resolve or validate practitioner references where available. It does not become AyuVahak's authentication or authorization authority. |
| MPI / client registry | Patient identity linkage across source records | Used for a scoped reference when available. AyuVahak does not match, merge, or establish a national patient identity. |
| Ambulance dispatch software | Fleet availability, assignment, dispatch, and movement control | Receives a purpose-limited transport request or supplies status. Dispatch decisions stay with the dispatch authority. |
| Generic interoperability engine | Mapping, routing, transformation, and transport between systems | Remains a replaceable integration layer. AyuVahak owns care-coordination semantics and workflow, not generic message mediation. |
| Generic BI platform | Cross-domain reporting and enterprise analytics | May consume governed aggregates. AyuVahak provides only coordination-oriented views and exports, not a general analytics warehouse. |

Overlap is expected at handoff points: an EHR may create a referral, an HIS may report beds, an HIE may carry the message, and a dispatch system may update vehicle status. Differentiation is the cross-organizational coordination state and accountability that those systems do not collectively own. Where an existing system already performs that workflow, AyuVahak should integrate or be omitted rather than duplicate an authoritative local process.

## Deployment overlap checks

DHIS2 Tracker can support patient-level workflows, and OpenHIM mediators can technically orchestrate exchanges. These are real overlap capabilities, not reasons to claim those platforms cannot coordinate cases. AyuVahak is appropriate only where the deployment selects it to own the durable referral/transfer lifecycle. Before onboarding a deployment that already uses Tracker, a mediator, or another application for this workflow, name the authoritative owner and avoid duplicate capture or split-brain state. See the [DHIS2 Tracker documentation](https://docs.dhis2.org/en/develop/using-the-api/dhis-core-version-master/tracker.html) and [OpenHIM mediator guide](https://openhim.org/docs/user-guide/mediators/).


