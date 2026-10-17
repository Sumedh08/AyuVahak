# Phase 12 Public Health Analytics and DHIS2 Specification

## Status and objective

**Design status:** canonical specification frozen only after Volume IV design review. **Implementation status:** not started and not authorized by this design document.

Phase 12 introduces a bounded Analytics context that computes reproducible, privacy-protected aggregate operational indicators from owner-produced reporting facts and optionally delivers approved aggregate values to configured DHIS2 partners through Phase 9. Operational workflows remain independent of Analytics and DHIS2.

## Problem and user outcome

Deployment administrators need to report approved operational aggregates without giving Analytics private cross-context database access, retaining patient records, or treating DHIS2 as an AyuVahak authority. Users need to explain the formula, source completeness, period, organization mapping, revision and delivery outcome for every exported value.

## Scope

- Analytics bounded context for approved definitions, owner reporting snapshots, aggregate snapshots/revisions, completeness and privacy decisions.
- Versioned indicators, explicit reporting calendars and organization attribution.
- Small-number suppression/coarsening policy hooks.
- DHIS2 aggregate DataValueSet mapping and export/reconciliation via Phase 9.
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
4. As a privacy officer, I want deployment-configurable suppression and coarsening so that small aggregates do not expose individuals.
5. As a reporting administrator, I want to map approved indicator versions and organizational units to pre-existing DHIS2 metadata so that target identifiers are controlled and versioned.
6. As an operator, I want value-level import outcomes so that a partial DHIS2 response is not recorded as full success.
7. As an operator after a timeout, I want an explicit unknown outcome and safe reconciliation so that a retry does not blindly overwrite a newer value.
8. As an origin/destination reporting owner, I want each indicator to declare attribution so that a federated Transfer is not double counted.
9. As an application operator, I want Analytics worker failure to delay reporting only so that care coordination remains available.

## Analytics ownership and source facts

Analytics owns `IndicatorDefinition` versions, aggregation policy, `AggregateSnapshot` and revision history, reporting quality/completeness, suppression decision, DHIS2 mapping versions and export operations. It owns no source event meaning or operational business fact.

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
- Versioned DHIS2 mapping references, when mapped.

Formula, dimension, meaning or privacy-policy changes that affect results create a new version. Historical versions remain queryable. Only synthetic demonstration indicators may be shipped as examples; they are not national or universal policy.

## Reporting periods and reproducibility

Every period is an explicit half-open interval `[start, end)` in a named IANA timezone. The resolved UTC boundaries are stored to make computation reproducible across daylight-saving or timezone database updates. Source snapshots identify owner version or cutoff and completeness. `AggregateSnapshot` records indicator/version, period, org/unit, bounded dimensions, result/status, input cutoffs, completeness, computation time, revision and any suppression state.

Statuses include `COMPLETE`, `PARTIAL`, `MISSING_SOURCE`, `STALE_SOURCE` and `NOT_APPLICABLE` as applicable. Missing/partial source is not zero. A complete zero may be reported only if the indicator definition allows it and every required source is complete for the same period/scope.

Late facts create an immutable new revision. A provisional snapshot may be recalculated within a configured correction window. Finalization is explicit. Changing a finalized result requires authorized correction with cause, prior/new revision linkage and audit. An earlier export record is never removed or silently rewritten.

## Privacy and small counts

An aggregate may still disclose sensitive information. Indicator definitions flag sensitive cells and specify required deployment policy. Deployment configuration may set a minimum-cell rule, approved dimensional coarsening or suppression. No universal threshold is hard-coded. If an indicator requires a policy and none is configured, its cell is not exportable. Free-text or patient-derived dimensions are prohibited. Suppressed values must not be sent as zero or omitted in a way that masquerades as a complete zero; local quality state must explicitly say suppression/non-exportable.

## DHIS2 target model and mapping

The initial target is DHIS2 Core 2.43 documented aggregate DataValueSet API semantics as of the research retrieval date, configured per partner. This is a compatibility basis, not a universal requirement that all peers use one version. The adapter uses the configured API profile and Phase 9 secure exchange; it must not hard-code a versioned route into business logic.

Mappings are administrator-approved, effective-dated and versioned. They bind indicator key/version plus organization/facility attribution and each permitted dimension to target partner, DHIS2 data element UID, orgUnit UID, period, category option combination, attribute option combination and data set where required. Target metadata is assumed preconfigured and approved. No automatic metadata creation or mutation is in scope. Target metadata identifiers never replace AyuVahak internal IDs.

Only aggregate DataValueSet is supported. A DataValue identity is the configured DHIS2 tuple (dataElement, period, orgUnit, category option combination, attribute option combination as applicable). Each outgoing value is computed from one complete/eligible local snapshot and carries no patient or case reference. `Tracker` and `Event` APIs are out of scope.

## Export lifecycle and reconciliation

An export operation binds one immutable aggregate snapshot revision, target partner/config version, mapping version, and a deterministic set of DataValue tuples. Prefer small deterministic batches, with per-value outcome tracking. Persist sanitized technical metadata and outcome; do not retain full request/response bodies by default.

Interpret structured DHIS2 import results at value level. HTTP 2xx alone is not acknowledgment. Accepted, updated, ignored, invalid and partial results remain distinguishable. A batch is acknowledged only when every expected value has an acceptable recorded outcome; otherwise it is `PARTIAL` or `RECONCILIATION_REQUIRED`.

No universal remote idempotency key, status lookup, all-or-nothing transaction or request replay guarantee is assumed. The local stable identity includes at minimum target partner, snapshot/revision, indicator version and canonical target tuple. A retry uses the same local operation identity. It may send the same tuple only when the configured named partner profile confirms that exact replacement is safe and a newer target value cannot be overwritten. Otherwise response loss becomes `OUTCOME_UNKNOWN`; use an approved authorized read-back to reconcile or require operator resolution. Never blindly resend an unknown value.

A correction creates a new immutable aggregate revision and a linked export operation. It explicitly replaces the target's exact configured DataValue tuple only under approved mapping/write-authority policy. Omission does not delete a prior value and does not mean zero. Explicit deletion is excluded from ordinary export and would need a separately authorized correction operation. Send numeric zero only when source is complete, the indicator semantics say zero, and target zero-storage behavior (including `zeroIsSignificant`) is known. If target behavior is unknown, block export until configured.

DHIS2 import summaries may reflect partial outcomes, and documentation does not promise all-or-nothing semantics, stable client idempotency, or durable operation lookup after response loss. Treat remote acknowledgment as delivery evidence, not public-health correctness.

## Authentication, security and observability

The adapter selects only a target-approved Phase 9 trust/auth profile. Supported auth varies by DHIS2 version and deployment; no universal OAuth client-credentials grant is assumed. Secrets remain Phase 9 references. OAuth identity/role does not create AyuVahak authorization. No caller may supply arbitrary URL. Endpoint safety, TLS, redirect, DNS/SSRF, payload size, timeout, credential redaction, retry and revocation rules reuse Phase 9.

Audit records link the authorized operator/service, purpose, indicator/version, period, scope, privacy decision, target partner, mapping version, export operation and outcome without storing PatientReference or clinical content. Logs, metrics and traces exclude patient/case IDs, aggregate payloads, tokens, credentials, raw query strings and high-cardinality target identifiers.

## Local-only testing

All development, CI, acceptance and conformance tests use a local deterministic mock HTTP server, synthetic metadata and synthetic aggregate payloads; a developer-controlled disposable local DHIS2 container is optional only if justified later. No public, demo, training, shared organizational or ministry DHIS2 system is a test target. The suite must pass with public Internet access disabled once dependencies are present.

The local mock must cover: successful aggregate import; 401/403; invalid data element/orgUnit/period/category tuple; 409 conflict where supported; 429; 5xx; timeout; partial import; response loss after synthetic commit; duplicate exact operation; changed fingerprint; target metadata/version change; safe correction; and unknown outcome that cannot be reconciled. Fixtures use obvious synthetic IDs, e.g. `SYN-ORG-001`, `SYN-FACILITY-A`, `SYN-DATAELEMENT-001`, `SYN-ORGUNIT-001`, `SYN-REPORTING-PERIOD-2026Q1`.

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
6. Missing suppression policy for a sensitive indicator blocks export; configured threshold edge tests show correct suppression/coarsening.
7. Origin/destination attribution prevents one federated Transfer from being silently counted twice; incompatible indicator versions cannot be combined.
8. Versioned mapping resolves synthetic indicator/org/dimension to the exact configured DHIS2 tuple; invalid or inactive mapping blocks send.
9. Local Phase 9 exchange sends only DataValueSet aggregate values; a patient/clinical marker sweep finds no PatientReference/IPS/narrative in export payload, DB, audit, events, logs, metrics, traces or Problem Details.
10. A local mock mixed import response records per-value partial result and does not mark the batch acknowledged.
11. A local mock response dropped after applying a value produces `OUTCOME_UNKNOWN`; no unsafe second write occurs without an approved reconciliation capability.
12. Same operation retry recovers the local recorded outcome; changed operation fingerprint conflicts.
13. Explicit zero is blocked when input is missing/partial or target zero semantics are unknown; omission never deletes or creates zero.
14. 401/403, invalid metadata, 409, 429, 5xx and timeout map to distinct safe classifications and only bounded safe failures are retried.
15. Analytics worker outage delays reporting only; source business transactions and care workflows remain available.
16. Full acceptance runs against local mock/synthetic fixtures with public network disabled; no public DHIS2 access is required.
17. Architecture checks prevent private schema access, DHIS2 dependency leakage and Analytics feedback into operational owners.

## Test seams

Test the owner reporting snapshot application ports, Analytics computation/revision service, suppression policy, mapping resolver, Phase 9 exchange operation, local mock response parser and reconciliation boundary. Use local PostgreSQL where snapshot revisions, idempotency, outbox recovery or export operations require transactions. Tests must inspect synthetic marker absence from every disallowed surface and fail closed when an unapproved endpoint is configured.

## Further reading

- [Volume IV architecture](../architecture/volume-4-resilient-operations-federation-public-health.md)
- [Context contracts](../architecture/volume-4-context-contracts.md)
- [State machines](../architecture/volume-4-state-machines.md)
- [Data ownership](../architecture/volume-4-data-ownership.md)
- [Privacy matrix](../architecture/volume-4-privacy-matrix.md)
- [Threat model](../architecture/volume-4-threat-model.md)
- [Official research](../research/volume-4-technical-research.md)
