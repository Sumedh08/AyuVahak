# AyuVahak Product Vision

## Problem

When a patient needs care that the current facility cannot provide, the referring team often has to coordinate by phone, paper, messaging, and local knowledge. Facility directories may be fragmented, capacity observations may be stale, acceptance is difficult to track, and patient context can be repeated or disclosed too broadly. Existing clinical and administrative systems are usually authoritative within their own organization but do not provide a shared, accountable cross-facility coordination workflow.

The result is coordination delay and uncertainty at the point where multiple organizations need to act together. AyuVahak addresses that coordination gap. It does not replace clinical records or clinical judgement.

## Mission

Enable healthcare organizations to discover appropriate care capacity, coordinate referrals and inter-facility transfers, exchange the minimum necessary patient context, and expose carefully scoped operational coordination information to existing healthcare and public-health infrastructure.

## Intended users

- Referring clinicians and trained referral operators who need another facility's capabilities and a timely response.
- Receiving-side clinical-suitability, operational-readiness, and acceptance authorities, mapped by each deployment to its local staff, teams, or governed processes.
- Hospital and district/regional operators who maintain service/capability information and coordinate exceptional cases.
- Transport dispatchers and crews who need a limited, actionable movement handoff.
- System operators, administrators, auditors, and public-health analysts who have distinct technical, governance, audit, or aggregate-information duties.
- Existing EHR/HIS, registries, identity providers, interoperability layers, DHIS2, LMIS, and routing systems that exchange only the information within their authority and purpose.

## Initial use cases

The first major workflow is emergency inter-facility referral and transfer. The future workflow must support:

1. stating a referring team's care requirement;
2. discovering facilities and filtering by eligibility;
3. retrieving attributed capacity and readiness observations;
4. producing an explainable, deterministic destination recommendation;
5. requesting receiving-facility acceptance;
6. recording a distinct, time-bounded reservation when confirmed by the responsible authority;
7. coordinating a transfer and, where appropriate, a transport handoff;
8. sharing only the minimum necessary patient context;
9. recording actual arrival and clinical handover;
10. recovering safely from stale evidence, rejection, timeout, conflicts, disconnection, cancellation, or changing conditions.

Other initial product uses are maintaining a provenance-aware coordination directory, recording operational capacity observations, and reporting limited non-identifiable coordination measures. They do not expand the product into clinical documentation, inventory management, or public-health indicator authoring.

## Initial value proposition

AyuVahak gives the referring and receiving teams a shared, auditable coordination thread: who requested care, what capabilities were needed, which evidence was considered and when, who accepted or rejected, whether capacity was reserved, how movement was arranged, and whether arrival and handover were acknowledged.

The product's value is accountable coordination across system boundaries. It should reduce avoidable searching and repeated handoffs while making uncertainty visible. It does not promise a clinical outcome, real-time truth from a stale source, a guaranteed bed, or a particular reduction in transfer time.

## Product principles

- Coordinate care; do not become an EHR/HIS or a central clinical-record warehouse.
- Own coordination facts and workflow state; reference, cache, or exchange only the external information needed for that workflow.
- Preserve category-specific authority, provenance, freshness, and purpose.
- Make recommendations deterministic, explainable, and advisory. A responsible human makes the decision.
- Share minimum necessary patient context with the people and systems that need it for the active case.
- Keep referral, acceptance, reservation, transfer, transport, arrival, and handover distinct.
- Degrade visibly and safely when a source or network is unavailable. Do not present queued or stale information as committed or current.
- Work toward low-resource and intermittent-connectivity environments without claiming offline conflict safety before it is designed and validated.
- Prefer open standards and replaceable integration adapters; standards are exchange contracts, not the internal domain model.
- Keep deployment and operations practical for health systems with limited infrastructure.

## Assumptions

- Participating organizations retain clinical and operational authority for their own care, facilities, capacity, and transport.
- A facility can provide an accountable human capacity observation even when it has no machine-readable integration.
- Capacity and readiness can change between recommendation and arrival; revalidation and human escalation are required.
- A clinical source can supply a scoped patient reference or summary, but the same exchange and legal basis will not exist in every deployment.
- Local policy, jurisdiction, staffing, connectivity, and existing registries vary. No single deployment profile is assumed.
- A deterministic first matching policy is safer to explain and govern than an opaque model in a safety-critical workflow.

## Long-term direction

AyuVahak is intended to become an open-source, adaptable coordination platform that can be deployed by a single hospital network or a larger jurisdiction, integrate with existing digital-health infrastructure, and federate carefully across organizational boundaries. Its license and community governance are future decisions. It may eventually qualify as or contribute to a Digital Public Good, but that status is an aspiration, not a present certification or product claim.

Long-term work may add other care-coordination workflows, stronger offline operation, regional operational views, and non-identifiable analytics after the emergency transfer workflow and governance model are validated. The architecture should allow these without centralizing clinical records or requiring heavyweight infrastructure everywhere.

## System-of-record boundary

AyuVahak is authoritative for the coordination cases, Transfer Requests and recorded responses, reservations it explicitly records, transfer coordination history, and audit of actions performed within the platform, but only where a deployment selects it as workflow owner. It is not authoritative for the patient identity, longitudinal clinical record, practitioner credentials, official facility registry, physical bed state, dispatch assignment, or public-health indicators owned elsewhere. See [Data ownership](../architecture/data-ownership.md).

