---
status: accepted
date: 2026-10-17
---

# ADR 0029: DHIS2 is a downstream aggregate export

## Context

DHIS2 supports several data models and import behaviors, but successful HTTP delivery does not establish indicator correctness or complete acceptance. Official Core 2.43 documentation describes aggregate DataValueSet imports and import summaries while not promising client idempotency, all-or-nothing imports, durable status lookup after a lost response, or omission-as-delete. Target metadata and authentication vary by deployment.

## Decision

Phase 12 exports only approved aggregate DataValueSet values from immutable Analytics snapshot revisions. It does not use Tracker/Event for patient-level export. DHIS2 remains downstream and is never an operational or identity authority. Preconfigured, versioned deployment mappings bind indicator/version and organization/dimension/period to target dataElement/orgUnit/category/attribute combinations. AyuVahak does not create target metadata by default.

Use Phase 9 partner trust and transport; select only a target-approved supported authentication profile. Store a stable local operation identity and per-value results. Treat import as potentially partial. A response loss yields OUTCOME_UNKNOWN unless an authorized read-back or explicitly safe same-tuple replacement resolves it. Omission is not zero or deletion. A correction is a new audited revision; explicit deletion is outside normal export. Emit zero only from complete source evidence with known target zero semantics.

## Consequences

- Local synthetic mock acceptance is sufficient; public/demo DHIS2 is never required.
- Acknowledgment means only that the target reported a transport/import outcome, not that the indicator is valid.
- Mapping, indicator and partner configuration changes are versioned and do not rewrite historical snapshots.
- Small-count disclosure policy is evaluated before export; missing required policy blocks the sensitive cell.

## Alternatives considered

- DHIS2 Tracker/Event export was rejected because no patient-level use is authorized and aggregate reporting is sufficient for this phase.
- Automatic metadata creation was rejected because target metadata is deployment-governed and may affect shared public-health configuration.
- Blind retries after timeout were rejected because remote idempotency/status lookup is not documented as universal.
- Treating omitted DataValues as deletion/zero was rejected because the official import documentation does not establish either semantic.

## References

- [Volume IV architecture](../architecture/volume-4-resilient-operations-federation-public-health.md)
- [Phase 12 specification](../specs/phase-12-public-health-analytics-dhis2-spec.md)
- [Official technical research](../research/volume-4-technical-research.md)
