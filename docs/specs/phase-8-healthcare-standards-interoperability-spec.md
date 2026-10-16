# Phase 8 specification — Healthcare Standards & Interoperability

**Status:** specification frozen. This file is the canonical Phase 8 behavior contract; implementation and acceptance status are recorded in `docs/reviews/phase-8-acceptance.md`.

**Volume:** III.

**Depends on:** frozen Volume I platform and Volume II owner contracts. Phase 8 may use those existing contracts but must not alter their semantic ownership.
**Architecture pointers:** [Volume III architecture](../architecture/volume-3-interoperability-security-transport.md), [context contracts](../architecture/volume-3-context-contracts.md), [data ownership](../architecture/data-ownership.md), [standards research](../research/volume-3-standards-research.md), ADRs 0004 and 0010 and the Volume III ADRs.

## Outcome

Add bounded, version-aware standards mappings only where they serve an existing AyuVahak owner capability: Directory discovery/source projection, Capacity operational evidence, and Referral-owned minimum patient-context retrieval. FHIR resources remain boundary representations. AyuVahak aggregates, identifiers, policies, authorization, lifecycle, and persistence remain owner-defined.

## In scope

- R4 4.0.1 FHIR adapter baseline, with configured profile/version handling and explicit unsupported-version behavior.
- Directory-owned mCSD query/source projection for a deployment that has an authoritative mCSD-compatible source.
- Optional, separately enabled mCSD feed/update synchronization only where the partner role and data-governance agreement support it.
- Referral-owned IPS parsing/validation and transient allowlisted context projection for an already authorized active transfer participant.
- Capacity-owned adapter mapping for a named external operational source only if its evidence semantics can be explicitly mapped into existing Capacity contracts and freshness policy.
- External identifier namespacing, provenance, source updates/deactivation, terminology/code handling, validation outcomes, and deterministic local conformance tests.

## Out of scope and prohibited

- No full FHIR server, EHR, EMR, MPI, patient chart, clinical-record warehouse, document archive, terminology server, generic HIE, generic integration engine, or public connector test.
- No FHIR-led redesign of Referral, Transfer, Capacity, Directory, or identity aggregates.
- No `Referral = ServiceRequest` or `Transfer = Task` one-to-one lifecycle equivalence.
- No mapping of Schedule/Slot to bed availability, operational Capacity, or Readiness.
- No arbitrary FHIR reference fetching, recursive Bundle crawling, unrestricted URL fetch, FHIR passthrough proxy, or direct adapter table access.
- No clinical data in Matching, Transfer, Transport, events, generic audit, ordinary logs, metrics, or traces.
- No standard is required to be present at every deployment. Local/manual operation remains valid where a partner or registry is absent.

## Version and profile behavior

- FHIR R4 4.0.1 is the first supported baseline, consistent with frozen ADR 0004 and current verified IPS, mCSD, and SMART publications.
- A partner adapter is configured against an explicit FHIR release and profile set. The request/response media type, conformance claim, capability metadata, and profile declarations are checked against that configuration. Payload shape is not used to guess a release. A version mismatch or required-profile failure is a permanent conformance error and is never retried as a transient network failure.
- Initial adapter conformance requires `application/fhir+json`. XML is not required by the baseline; it may be added only for a named partner need, with hardened parser settings and explicit XML security tests. An adapter must not claim a broader IHE/HL7 conformance role than it actually verifies.
- R4B 4.3.0 and R5 5.0.0 require explicit version-specific adapter/mapping and fixtures. No transparent conversion or semantic equivalence is promised. A conversion reports unsupported/lost/ambiguous semantics and fails or yields an explicitly partial result according to the owner contract.
- Retrieve only configured resource types and bounded pages/counts. Do not automatically follow arbitrary absolute references. A reference is resolved only if its origin and resource type are covered by the partner trust profile and an owner purpose requires it.
- Required resource/profile absence, malformed data, unknown required codes, duplicate conflicting identifiers, and reference cycles produce explicit rejection/partial/needs-reconciliation outcomes. They do not create default-positive domain facts.

## Resource use and owner mapping

| FHIR concept | AyuVahak owner and purpose | Required projection | Ignored / never inferred | Persistence and failure behavior |
| --- | --- | --- | --- | --- |
| `Organization` | Directory; administrative organization source | Namespaced source identifier, source status/version, minimal display/type/hierarchy values needed by Directory policy | AyuVahak organization identity, trust, authorization, or ownership cannot be inferred from a matching code | Bounded Directory projection + provenance. Identifier collision is a conflict, not an automatic merge. |
| `Location` | Directory; physical/logical care location | Namespaced ID, linked organization, type/status, address/coordinates only if policy permits and required for discovery | No room/bed presence or operational availability from a location record | Bounded Directory projection; withdrawn/deactivated source status is retained as source evidence, then Directory applies its deactivation policy. |
| `HealthcareService` | Directory; service directory facts | Namespaced ID, organization/location links, service type, status, hours/contacts only if included in a named profile | No capability guarantee or current capacity/readiness from service existence/hours | Bounded Directory projection and provenance; unresolved references prevent a usable projection and are reported. |
| `Practitioner`, `PractitionerRole` | Directory only when a deployment has a concrete provider discovery or attribution use | Opaque namespaced external reference and only fields required for the approved use | No provider identity authority or global practitioner registry is created | Prefer reference-only; do not ingest full workforce profile by default. Conflicting source IDs remain separate. |
| `Endpoint` | Directory may retain discovery metadata, but Phase 9 owns network trust enforcement | Endpoint type, source reference, and partner/config association | Endpoint.address is never used as an arbitrary trusted destination or credential-bearing URL | Keep as untrusted discovery metadata; network calls require separate administrator-approved endpoint configuration. |
| `Patient` | Referral adapter uses only to resolve the existing external `PatientReference` for a permitted context request | No durable Patient projection | No local patient identity, matching, MPI, demographic profile, or longitudinal identity resolution | Transient processing only. A mismatch returns explicit conflict/denial; never substitute another patient. |
| IPS `Bundle` / `Composition` | Referral; retrieve a minimum pre-arrival clinical summary when separately authorized | Explicit allowlist of clinically relevant fields approved by deployment policy; source, profile, version, source time, received time | IPS completeness, clinical correctness, currentness, patient match, and permission are not inferred from profile validity | Full resource graph transient in memory only; no durable raw document, patient chart, or attachment. If parsing fails, return unavailable/invalid context and retain minimal audit/provenance metadata. |
| `ServiceRequest` | Optional external representation of a request for a clinical service, mapped by Referral only under a named exchange profile | External ID and explicit fields that map to the existing Referral contract | It is not the full Referral lifecycle, care-requirement model, candidate recommendation, or transfer acceptance | Retain only owner-scoped external reference and mapped state required for reconciliation; reject unrepresentable state rather than mislabel it. |
| `Task` | Optional external work status representation; mapping belongs to the context that owns the represented work | External ID, requester/owner references as needed, mapped task status and version | Not AyuVahak Transfer aggregate; status does not imply Acceptance, Reservation, or Arrival | Reference plus mapped outcome/provenance only; out-of-order or contradictory status triggers reconciliation. |
| `Encounter` | No Phase 8 use | None | Clinical encounter documentation and full treatment record remain in clinical systems | Not ingested or persisted. |
| `Provenance` | Owner adapter may read source attribution/time where the selected profile defines it | Source identity, source version/time, transformation reference | Does not replace AyuVahak audit, prove clinical truth, or justify storing source payload | Retain only bounded provenance fields that the owner needs. |
| `Schedule`, `Slot` | No Capacity readiness mapping by default; a future source-specific adapter requires an explicit Capacity policy | At most appointment-window facts where a separately defined use exists | A free Slot is not an available bed, staff, equipment, clinical suitability, or operational readiness | Do not ingest for matching/readiness in Phase 8. |

An adapter version/profile mapper translates to the owner’s established application contract. FHIR terminology codes are carried with canonical system URI and version where available. An unknown optional code may be preserved as an uninterpreted source code or mark a projection incomplete; an unknown code needed for an eligibility/safety rule makes that evidence indeterminate. Online terminology service availability is not a runtime prerequisite.

## mCSD / Directory synchronization

- Directory is the semantic owner. An adapter invokes Directory-owned import/reconciliation commands; it never inserts or updates Directory persistence directly.
- Initial discovery uses AyuVahak Directory as an mCSD Query Client initiating ITI-90 against the configured source Directory. If agreed, ITI-91 uses AyuVahak as Update Client requesting changed facts from that external Directory. If agreed, ITI-130 uses the external registry as Data Source sending feed updates to the AyuVahak Directory actor. Both optional paths flow source facts into the Directory-owned AyuVahak projection; AyuVahak does not create/update/delete records in an external master. mCSD 4.0.0 is a Trial Implementation based on R4. Each option is separately enabled, tested, and named in a deployment agreement.
- Source association key is `(source-system namespace, resource type, external identifier)` with source version/history when available. Similar textual IDs from two systems are distinct.
- Preserve source organization, endpoint/config identity, resource version/ETag or equivalent when supplied, source-reported time, received time, mapper/profile version, and outcome. Do not keep raw resources by default.
- An explicit source delete/deactivation event becomes a source-status observation. Directory policy decides whether to deactivate, retain an inactive projection, or mark disputed. Absence from a search result, partial page, failed cursor, or expired cache is not a deletion.
- If the source has a durable cursor/watermark, persist only its opaque checkpoint and source association. If not, run bounded reconciliation over a source-declared complete scope; report incomplete runs and never sweep-delete based on a partial read.
- Duplicate updates are idempotent. Older source versions cannot overwrite newer facts. Same-version contradictory content becomes a conflict. Corrected mapping/profile versions require reprocessing via explicit owner command and audit, not direct table rewrite.
- Source unavailable means existing Directory projection is usable only within Directory’s freshness/status policy. Expose stale/unknown provenance; never present cached data as verified current.

## IPS disclosure and patient context

1. Recipient authenticates locally and requests context for an active Transfer.
2. Transfer verifies the actor’s current grant, selected-recipient relationship, and relevant execution organization/facility. Recipient organization membership alone is insufficient.
3. Referral validates the source-scoped `PatientReference`, purpose, configured legal/consent policy hooks and disclosure scope. It independently authorizes the read.
4. Referral records required sensitive-read audit before disclosure; failure of required audit denies the disclosure.
5. Referral invokes its context-owned source port. The adapter requests only the configured IPS representation, applies bounded parsing/profile validation, rejects unapproved references/attachments, and returns a minimal transient projection with source/freshness status.
6. The API response is delivered only to the authorized recipient. Neither Transfer nor the generic event/audit path receives patient content.

No jurisdiction-independent consent rule or “emergency implies access” rule is hard-coded. Deployments configure legal basis, consent reference/decision, purpose-of-use, and any future break-glass policy. The default when policy is absent or indeterminate is deny. No IPS payload cache is used. Any later cache proposal requires separate architecture review, purpose, access controls, expiry, deletion, and audit decision.

## Acceptance and test seams

All data is synthetic (for example `SYN-PATIENT-001`, `SYN-FACILITY-A`). All tests run against fixtures or locally controlled mock servers and local disposable infrastructure. No public FHIR server, SMART sandbox, HIE, or registry may be required.

- **Mapping seam:** fixtures for R4 Organization, Location, HealthcareService, Endpoint, PractitionerRole, ServiceRequest, Task, and IPS Bundle are mapped through owner application contracts; verify internal values remain owner concepts.
- **Profile/version seam:** valid R4 profile; unsupported version/profile; malformed resource; wrong content type; unknown required terminology; missing required linked resource; conflicting external identifier; stale resource version; untrusted absolute reference; oversized Bundle; excessive nesting/resource/page count.
- **Directory seam:** duplicate idempotent feed; update/deactivation; delete event; absent resource in partial query; complete-scope reconciliation; cursor restart; older version; source outage/stale projection; conflict resolution. Verify no direct Directory table writes from adapter.
- **mCSD direction seam:** verify ITI-90/optional ITI-91 are read requests from AyuVahak to the external Directory and optional ITI-130 feed messages flow from the external Data Source to AyuVahak; prove there is no write-back operation to the external master.
- **Capacity seam:** show a Schedule/Slot fixture cannot produce READY/available capacity without an explicit approved Capacity semantic mapping and evidence policy.
- **IPS privacy seam:** disclosure denied without selected recipient/purpose; source outage; valid minimum context; audit-store unavailable; no raw bundle or PatientReference copied into other contexts/events/logs/metrics/traces.
- **Boundary seam:** architecture rules keep FHIR and parser libraries in adapters and disallow FHIR dependencies in domain packages.

Acceptance passes only when the owner contract outcomes, persistence/minimization rules, provenance, and failure states are verified with local synthetic data. No test may write to or depend on an external system.
