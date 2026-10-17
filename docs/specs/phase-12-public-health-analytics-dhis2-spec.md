# Phase 12 Public Health Analytics and DHIS2 Specification

## Status and objective

**Design status:** specification frozen in the Volume IV design freeze. **Implementation status:** not started; a separate human-authorized master prompt is required.

Phase 12 introduces a bounded Analytics context that computes reproducible, privacy-protected aggregate operational indicators from owner-produced reporting facts and optionally delivers approved aggregate values to configured DHIS2 partners through Phase 9. Operational workflows remain independent of Analytics and DHIS2.

## Problem and user outcome

Deployment administrators need to report approved operational aggregates without giving Analytics private cross-context database access, retaining patient records, or treating DHIS2 as an AyuVahak authority. Users need to explain the formula, source completeness, period, organization mapping, revision and delivery outcome for every exported value.

## Scope

- Analytics bounded context for approved definitions, owner reporting snapshots, aggregate snapshots/revisions, completeness and privacy decisions.
- Versioned indicators, explicit reporting calendars and organization attribution.
- Release-set small-number disclosure control with complementary suppression, coarsening and prior-release review.
- DHIS2 aggregate DataValueSet mapping and export/reconciliation via Phase 9.
- Optional aggregate-to-aggregate exchange between explicitly trusted Analytics peers, with exact compatibility checks and source provenance.
- Local-only synthetic acceptance and explicit failure/unknown handling.

## Non-goals

- DHIS2 replacement or operational source of truth.
- Patient-level Tracker/Event export, PatientReference, patient identifiers, names, IPS, diagnosis, clinical narrative or attachments.
- Clinical/patient data warehouse, national data lake, generic BI/analytics platform or cross-context private SQL joins.
- Automatically creating DHIS2 users, programs, org units, data elements, datasets, indicators, category options or credentials.
- Public/demo/shared DHIS2 as a development, CI, acceptance or conformance dependency.
- Synchronous analytics dependency on Referral, Transfer, Capacity, Matching or Transport transactions.
- Silent zero for missing/partial sources, unversioned formula changes, implicit delete by omission or blind overwrite after unknown response.

## User stories

1. As a deployment reporting administrator, I want approved indicator definitions versioned so that formula changes do not rewrite historical values.
2. As a reporting operator, I want to see completeness and source cutoffs so that missing or stale input is not mistaken for zero.
3. As an auditor, I want each snapshot to identify indicator version, period, organization, dimensions, source cutoff and revision so that a value can be reproduced.
4. As a privacy officer, I want release-set disclosure controls across current and prior outputs so that small aggregates cannot be recovered by differencing cells, totals, periods or revisions.
5. As a reporting administrator, I want to map approved indicator versions and organizational units to pre-existing DHIS2 metadata so that target identifiers are controlled and versioned.
6. As an operator, I want value-level import outcomes so that a partial DHIS2 response is not recorded as full success.
7. As an operator after a timeout, I want an explicit unknown outcome and safe reconciliation so that a retry does not blindly overwrite a newer value.
8. As an origin/destination reporting owner, I want each indicator to declare attribution so that a federated Transfer is not double counted.
9. As an application operator, I want Analytics worker failure to delay reporting only so that care coordination remains available.

## Analytics ownership and source facts

Analytics owns AyuVahak's `IndicatorDefinition` calculation versions, aggregation policy, `AggregateSnapshot` and revision history, reporting quality/completeness, suppression decisions, DHIS2 mapping versions and export operations. The authorized public-health authority owns official indicator interpretation and DHIS2 target metadata. Analytics may record an approved external authority identifier/version and mapping, but must not present its local formula as an authoritative public-health definition. Analytics owns no source event meaning or operational business fact.

Each source owner implements a bounded reporting snapshot contract for approved measure keys and scoped periods. It computes its own values within its own database and returns only approved aggregate measures, organization/facility scope, bounded category dimensions, exact time interval, owner version/source cutoff, completeness/status and generated time. Analytics may not import owner repositories, query private schemas, perform cross-schema joins, infer missing values, or reconstruct an unofficial owner ledger from raw events.

Candidate source measures are enabled only when an approved definition and owner contract exist. Examples may include submitted/resolved Referral counts, Transfer accepted/completed/unfulfilled counts, Capacity evidence freshness coverage, or Transport requested/fulfilled counts. Matching latency or eligibility measures require a clear operational purpose and bounded attribution. No source fact is automatically included. No person, referral, transfer, patient, or arbitrary free-text dimension is returned.

Analytics processing is asynchronous and recoverable from durable owner snapshot requests/watermarks. Owner transactions and public-health workflows do not wait on indicator computation. Internal outbox events may wake or sequence work but do not change owner authority.

## Indicator definition and versioning

An immutable indicator version includes:

- Stable key, version, description, owner-approved purpose and effective interval.
- Formula, numerator, denominator, aggregation function and source owner measure keys.
- Allowed bounded dimensions and organization attribution rule (origin, destination, or explicitly distinct dimensions).
- Reporting calendar, IANA timezone, period boundary rule, resolved UTC interval and relevant timezone database version.
- Completeness and source freshness requirements; null/missing semantics.
- Sensitivity classification, required suppression/coarsening policy and export eligibility.
- Approved public-health authority identifier and definition version, when an official external definition is being implemented; this is provenance, not authority transfer.
- Versioned DHIS2 mapping references, when mapped.

Formula, dimension, meaning or privacy-policy changes that affect results create a new version. Historical versions remain queryable. Only synthetic demonstration indicators may be shipped as examples; they are not national or universal policy.

## Reporting periods and reproducibility

Every period is an explicit half-open interval `[start, end)` in a named IANA timezone. The resolved UTC boundaries are stored to make computation reproducible across daylight-saving or timezone database updates. Source snapshots identify owner version or cutoff and completeness. `AggregateSnapshot` records indicator/version, period, org/unit, bounded dimensions, result/status, input cutoffs, completeness, computation time, revision and any suppression state.

Statuses include `COMPLETE`, `PARTIAL`, `MISSING_SOURCE`, `STALE_SOURCE` and `NOT_APPLICABLE` as applicable. Missing/partial source is not zero. A complete zero may be reported only if the indicator definition allows it and every required source is complete for the same period/scope.

Late facts create an immutable new revision. A provisional snapshot may be recalculated within a configured correction window. Finalization is explicit. Changing a finalized result requires authorized correction with cause, prior/new revision linkage and audit. An earlier export record is never removed or silently rewritten.

## Privacy and small counts

An aggregate may still disclose sensitive information. Indicator definitions flag sensitive cells and specify required deployment policy. Before each value is disclosed, Analytics evaluates the candidate together with complementary cells, totals/subtotals, dimensions, overlapping periods, revisions and all prior releases that could be differenced to infer a suppressed value. The release baseline includes prior AyuVahak outputs and any known target-side or external outputs. If that external baseline is unknown, sensitive release requires a conservative approved coarsening or review; otherwise it is blocked. Deployment configuration may set minimum-cell rules, approved dimensional coarsening, complementary suppression, withholding of related outputs, or review. No universal threshold or algorithm is hard-coded. If a required policy, release history, or safe decision is unavailable, the candidate and any related outputs are not exportable. A value with an unknown external outcome is conservatively treated as potentially disclosed for subsequent decisions. Free-text or patient-derived dimensions are prohibited. Suppressed values must not be sent as zero or omitted in a way that masquerades as a complete zero; local quality state must explicitly say suppression/non-exportable. A policy change cannot make an unsafe historical release disappear from disclosure analysis.

DHIS2 export and federated Analytics publication share one local Analytics disclosure-reservation transaction boundary. A disclosure domain has an immutable lineage ID, distinct from the versioned policy/mapping that assigns outputs to it. The lineage covers outputs that may be combined, differenced, or otherwise jointly reveal a protected cell, regardless of destination, channel, period, revision, or dimensional projection. The default is one conservative lineage per privacy-policy/release scope; narrower lineages are allowed only when the approved policy proves they cannot overlap or be recombined. Every output maps to every affected lineage, including related totals, subtotals, complementary cells, overlapping periods, revisions, alternate dimensions, and historical outputs.

A policy or mapping revision must not reset disclosure history or create a fresh lock namespace. Its activation atomically records a versioned mapping from outputs to stable lineages while locking the union of old and proposed lineages; prior and in-flight reservations remain visible under that union. A merge retains all prior lineage associations. A split retains the former shared lineage for every descendant that can still be recombined with earlier releases; an old lineage may be retired only after an approved review proves historical and future outputs cannot be recombined. If Analytics cannot prove complete mapping or lineage continuity, it uses the broader existing lineage or fails closed. It must never choose a narrower key merely to increase concurrency.

Before sending, the owner serializes all affected lineage keys, evaluates against disclosed values and in-flight reservations, then atomically records the policy/history checkpoint and a stable reservation containing the full lineage set. Every Phase 12 outbound path must use this gate. Reservations count as potentially disclosed for subsequent checks; acknowledgments become disclosed, and `OUTCOME_UNKNOWN` remains potentially disclosed. A reservation may be withdrawn only when Phase 9 proves that no request bytes could have left AyuVahak. Retries use the same reservation and operation identity. Do not hold a database transaction/lock across network I/O. Acquire overlapping keys in deterministic order. Missing/ambiguous domain mapping, broken lineage, unavailable release history, or reservation-store failure blocks sending. This serialization prevents concurrent individually-small exports from defeating complementary suppression.

## DHIS2 target model and mapping

The initial target is DHIS2 Core 2.43 documented aggregate DataValueSet API semantics as of the research retrieval date, configured per partner. This is a compatibility basis, not a universal requirement that all peers use one version. The adapter uses the configured API profile and Phase 9 secure exchange; it must not hard-code a versioned route into business logic.

Mappings are administrator-approved, effective-dated and versioned. They bind indicator key/version plus organization/facility attribution and each permitted dimension to target partner, DHIS2 data element UID, orgUnit UID, period, category option combination, attribute option combination and data set where required. Automated writes are enabled only when the approved target profile establishes exclusive AyuVahak write ownership for the affected tuples or documents a conditional/versioned update guard that prevents stale overwrite; otherwise export is blocked pending an explicitly authorized operator-mediated path. Target metadata is assumed preconfigured and approved. No automatic metadata creation or mutation is in scope. Target metadata identifiers never replace AyuVahak internal IDs.

Only aggregate DataValueSet is supported. A DataValue identity is the configured DHIS2 tuple (dataElement, period, orgUnit, category option combination, attribute option combination as applicable). Each outgoing value is computed from one complete/eligible local snapshot and carries no patient or case reference. The initial profile uses synchronous imports only and sends exactly one DataValue per DataValueSet request, so documented aggregate import summary counts are attributable to a single target tuple. It must not enable an asynchronous import option or treat an accepted job/task ID as a completed value. Multi-value batching is unsupported unless a separately approved target profile documents an unambiguous per-value result contract and its acceptance tests prove it. `Tracker` and `Event` APIs are out of scope.

## Export lifecycle and reconciliation

An export operation binds one immutable aggregate snapshot revision, target partner/config version, mapping version, and exactly one target DataValue tuple in the initial profile. It passes release-set disclosure review immediately before send. The initial profile uses synchronous import only; it does not set an async option or accept a job/task identifier as a completed outcome. A future asynchronous profile must durably retain the remote task identity and keep the tuple barrier until a documented terminal task result or authorized reconciliation.

Analytics also enforces a durable single-flight barrier per configured target DataValue identity (partner plus the complete DHIS2 tuple), independent of snapshot revision. A later revision for that tuple cannot be sent while an earlier operation may still apply remotely. A durable active-operation claim is created before send; no lock is held across network I/O. A documented terminal response or authorized read-back/operator reconciliation must resolve the prior operation before the next revision can claim the tuple. A crash/lease expiry during or after send is treated as `OUTCOME_UNKNOWN`, and the barrier remains held: a local lease timeout cannot prove that a remote request will not arrive late. The initial profile has no assumed target-side conditional-write/fencing guarantee, so an unresolved or unknown older operation blocks a newer correction rather than risking stale overwrite. Persist sanitized technical metadata and the documented import summary; do not retain full request/response bodies by default.

Interpret only outcomes the configured DHIS2 profile documents. HTTP 2xx alone is not acknowledgment. For a one-value request, `imported=1` or `updated=1` acknowledges delivery of that target value, not its analytical correctness. `ignored=1` is ambiguous (for example, unchanged and invalid may not be distinguishable from aggregate counts); it is not success without an authorized read-back that confirms the expected value or explicit operator reconciliation. Where the configured target profile documents an explicit invalid/rejected outcome, treat it as permanent for that mapping/revision until corrected. If the target reports only aggregate ignored/count outcomes, require read-back or operator reconciliation instead of inferring a per-value rejection. Because each operation contains one target value, partial acceptance is represented as separate acknowledged and failed/unknown operations rather than an inferred per-item mapping in one batch. Do not claim that a local mock proves an undocumented remote response detail.

No universal remote idempotency key, status lookup, all-or-nothing transaction or request replay guarantee is assumed. The local stable identity includes at minimum target partner, snapshot/revision, indicator version and canonical target tuple. A retry uses the same local operation identity and retains the tuple barrier. It may send the same tuple only when the configured named partner profile confirms that exact replacement is safe and a newer target value cannot be overwritten. Otherwise response loss becomes `OUTCOME_UNKNOWN`; use an approved authorized read-back to reconcile or require operator resolution. Never blindly resend an unknown value or allow a newer revision to overtake it.

A correction creates a new immutable aggregate revision, operation identity and disclosure reservation; the prior operation remains unchanged and auditable. It explicitly replaces the target's exact configured DataValue tuple only under approved mapping/write-authority policy and the same single-flight barrier. Automated correction requires exclusive AyuVahak write ownership for the tuple or a documented target conditional/versioned update guard; otherwise it is blocked for an authorized operator-mediated path. Omission does not delete a prior value and does not mean zero. Explicit deletion is excluded from ordinary export and would need a separately authorized correction operation. Send numeric zero only when source is complete, the indicator semantics say zero, and target zero-storage behavior (including `zeroIsSignificant`) is known. If target behavior is unknown, block export until configured.

DHIS2 import summaries may reflect partial outcomes, and documentation does not promise all-or-nothing semantics, stable client idempotency, or durable operation lookup after response loss. Treat remote acknowledgment as delivery evidence, not public-health correctness.

## Authentication, security and observability

The adapter selects only a target-approved Phase 9 trust/auth profile. Supported auth varies by DHIS2 version and deployment; no universal OAuth client-credentials grant is assumed. Secrets remain Phase 9 references. OAuth identity/role does not create AyuVahak authorization. No caller may supply arbitrary URL. Endpoint safety, TLS, redirect, DNS/SSRF, payload size, timeout, credential redaction, retry and revocation rules reuse Phase 9.

Audit records link the authorized operator/service, purpose, indicator/version, period, scope, privacy decision, target partner, mapping version, export operation and outcome without storing PatientReference or clinical content. Logs, metrics and traces exclude patient/case IDs, aggregate payloads, tokens, credentials, raw query strings and high-cardinality target identifiers.

## Local-only testing

All development, CI, acceptance and conformance tests use a local deterministic mock HTTP server, synthetic metadata and synthetic aggregate payloads; a developer-controlled disposable local DHIS2 container is optional only if justified later. No public, demo, training, shared organizational or ministry DHIS2 system is a test target. The suite must pass with public Internet access disabled once dependencies are present.

The local mock must cover: successful aggregate import; 401/403; invalid data element/orgUnit/period/category tuple using only the result detail documented by the configured profile; 409 conflict where supported; 429; 5xx; timeout; separate operation success/failure and partial export across operations; response loss after synthetic commit; duplicate exact operation; changed fingerprint; target metadata/version change; safe correction; and unknown outcome that cannot be reconciled. Fixtures use obvious synthetic IDs, e.g. `SYN-ORG-001`, `SYN-FACILITY-A`, `SYN-DATAELEMENT-001`, `SYN-ORGUNIT-001`, `SYN-REPORTING-PERIOD-2026Q1`.

## Architecture enforcement requirements

- Analytics imports owner application reporting contracts only; no private persistence/repository imports or cross-schema SQL.
- DHIS2 types/client dependencies exist only in Analytics adapter packages.
- Owner domains do not import Analytics or DHIS2 types.
- Analytics cannot import PatientReference, IPS, patient, clinical, Referral, Transfer or Transport aggregate models.
- DHIS2 adapter uses Phase 9 exchange and does not own endpoint trust, token, TLS or retry infrastructure.
- No Analytics result changes Capacity, Matching, Referral, Transfer, Transport or Directory state.
- No Volume V package, tracker export, public health event API, or external metadata write is introduced.

## Acceptance criteria

1. A versioned definition is immutable; changing formula or semantics creates a distinct version and leaves old snapshots reproducible.
2. A source owner returns a bounded period/scope snapshot from its own data; Analytics has no direct repository/table access.
3. Cross-context source failure produces MISSING/PARTIAL/STALE, never zero; only complete evidence can produce numeric zero.
4. Period boundaries across timezone/DST changes are deterministic and stored with the snapshot.
5. Late data creates a revision; finalization requires explicit action; correction records old/new revision, cause and audit.
6. Missing suppression policy, relevant release history, or safe external release baseline for a sensitive indicator blocks the related release set unless conservative coarsening/review is approved; tests cover complementary suppression and subtraction across cells, totals, periods, revisions and prior releases.
7. Two concurrent DHIS2/peer export workers mapped to overlapping disclosure domains serialize through the same reservation gate; all outputs that policy says could combine/difference share at least one stable lineage, with the conservative policy-domain key used whenever narrower separation is not proven. A pair that would expose a withheld cell cannot both pass based on the same stale checkpoint (unless policy-approved coarsening makes their combined release safe). Lost response remains reserved as potentially disclosed; retry reuses the same reservation. Tests use real local PostgreSQL transactions and race both outbound paths.
8. Origin/destination attribution prevents one federated Transfer from being silently counted twice; incompatible indicator versions cannot be combined. Optional aggregate federation accepts only authorized, exact-compatible version fingerprints and deduplicates stable source snapshot IDs; changed fingerprints conflict.
9. Versioned mapping resolves synthetic indicator/org/dimension to the exact configured DHIS2 tuple; invalid or inactive mapping blocks send.
10. Local Phase 9 exchange sends only DataValueSet aggregate values; a patient/clinical marker sweep finds no PatientReference/IPS/narrative in export payload, DB, audit, events, logs, metrics, traces or Problem Details.
11. Each initial-profile DataValueSet request contains exactly one DataValue. Separate operations can demonstrate partial export without attributing an aggregate count to an individual value. The parser does not infer undocumented per-value detail from multi-value summaries.
12. A local mock response dropped after applying a value produces `OUTCOME_UNKNOWN`; no unsafe second write occurs without an approved reconciliation capability. An `ignored=1` summary remains unresolved until authorized read-back or operator reconciliation.
13. Same operation retry recovers the local recorded outcome; changed operation fingerprint conflicts.
14. Explicit zero is blocked when input is missing/partial or target zero semantics are unknown; omission never deletes or creates zero.
15. 401/403, invalid metadata, 409, 429, 5xx and timeout map to distinct safe classifications and only bounded safe failures are retried.
16. Analytics worker outage delays reporting only; source business transactions and care workflows remain available.
17. Optional peer aggregate exchange uses local controlled endpoints and proves authorization/revocation, exact-version compatibility, source provenance, exact duplicate recovery, changed-fingerprint conflict and double-count attribution behavior.
18. Full acceptance runs against local mock/synthetic fixtures with public network disabled; no public DHIS2 access is required.
19. Architecture checks prevent private schema access, DHIS2 dependency leakage and Analytics feedback into operational owners.
20. Policy/mapping rollover preserves stable disclosure lineages and old/in-flight reservations; a PostgreSQL race spanning old/new policy versions cannot bypass the release gate. A split cannot discard a shared historical lineage where outputs remain recombinable.
21. Two revisions targeting the same DHIS2 tuple cannot be in flight together. While an older send is in flight or `OUTCOME_UNKNOWN`, a newer correction remains blocked across worker restart; it becomes eligible only after documented completion or authorized reconciliation, and no delayed older operation can overwrite it afterward.
22. The initial DHIS2 profile rejects asynchronous-import configuration/results as unsupported; an import job/task ID cannot clear the tuple barrier. A separately approved future async profile must retain the task ID and hold the barrier until its terminal result is reconciled.

## Test seams

Test the owner reporting snapshot application ports, Analytics computation/revision service, release-set disclosure policy (including prior-release differencing), concurrent disclosure reservation gate, mapping resolver, Phase 9 export and peer exchange operations, local mock response parser and reconciliation boundary. Use real local PostgreSQL transactions to race two DHIS2/peer releases on overlapping disclosure keys; mocks must not replace the lock/serialization semantics. Tests must inspect synthetic marker absence from every disallowed surface and fail closed when an unapproved endpoint is configured. Mock import summaries may assert only the documented summary contract; they must not imply that an external target reports more granular outcomes than its profile documents.

## Further reading

- [Volume IV architecture](../architecture/volume-4-resilient-operations-federation-public-health.md)
- [Context contracts](../architecture/volume-4-context-contracts.md)
- [State machines](../architecture/volume-4-state-machines.md)
- [Data ownership](../architecture/volume-4-data-ownership.md)
- [Privacy matrix](../architecture/volume-4-privacy-matrix.md)
- [Threat model](../architecture/volume-4-threat-model.md)
- [Official research](../research/volume-4-technical-research.md)
